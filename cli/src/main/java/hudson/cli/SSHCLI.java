/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.cli;

import hudson.util.QuotedStringTokenizer;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import static java.util.logging.Level.FINE;
import java.util.logging.Logger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.future.WaitableFuture;
import org.apache.sshd.common.util.io.NoCloseInputStream;
import org.apache.sshd.common.util.io.NoCloseOutputStream;
import org.apache.sshd.common.util.security.SecurityUtils;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Implements SSH connection mode of {@link CLI}.
 * In a separate class to avoid any class loading of {@code sshd-core} when not using {@code -ssh} mode.
 * That allows the {@code test} module to pick up a version of {@code sshd-core} from the {@code sshd} module via {@code jenkins-war}
 * that may not match the version being used from the {@code cli} module and may not be compatible.
 */
class SSHCLI {

    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE", justification = "Due to whatever reason FindBugs reports it fot try-with-resources")
    static int sshConnection(String jenkinsUrl, String user, List<String> args, PrivateKeyProvider provider, final boolean strictHostKey) throws IOException {
        Logger.getLogger(SecurityUtils.class.getName()).setLevel(Level.WARNING); // suppress: BouncyCastle not registered, using the default JCE provider
        URL url = new URL(jenkinsUrl + "login");
        URLConnection conn = url.openConnection();
        CLI.verifyJenkinsConnection(conn);
        String endpointDescription = conn.getHeaderField("X-SSH-Endpoint");

        if (endpointDescription == null) {
            CLI.LOGGER.warning("No header 'X-SSH-Endpoint' returned by Jenkins");
            return -1;
        }

        CLI.LOGGER.log(FINE, "Connecting via SSH to: {0}", endpointDescription);

        int sshPort = Integer.parseInt(endpointDescription.split(":")[1]);
        String sshHost = endpointDescription.split(":")[0];

        StringBuilder command = new StringBuilder();

        List<String> sshclientPropertyNames = new ArrayList<String>();
        List<String> sshclientPropertyValues = new ArrayList<String>();
        for (int i=0; i<args.size(); i++) {
			if (args.get(i).equals("-sshprop") && ((i + 1) < args.size())) {
				String argValue = args.get(i + 1);
				int equalIndex = argValue.indexOf("=");
				if (equalIndex >= 1) {
					sshclientPropertyNames.add(argValue.substring(0, equalIndex));
					sshclientPropertyValues.add(argValue.substring(equalIndex + 1, argValue.length()));
					i++;
					continue;
				}
			}

            command.append(QuotedStringTokenizer.quote(args.get(i)));
            command.append(' ');
        }

        try(SshClient client = SshClient.setUpDefaultClient()) {
			for (int i = 0; i < sshclientPropertyNames.size(); i++) {
				PropertyResolverUtils.updateProperty(client, sshclientPropertyNames.get(i),
						sshclientPropertyValues.get(i));
			}

            KnownHostsServerKeyVerifier verifier = new DefaultKnownHostsServerKeyVerifier(new ServerKeyVerifier() {
                @Override
                public boolean verifyServerKey(ClientSession clientSession, SocketAddress remoteAddress, PublicKey serverKey) {
                    CLI.LOGGER.log(Level.WARNING, "Unknown host key for {0}", remoteAddress.toString());
                    return !strictHostKey;
                }
            }, true);

            client.setServerKeyVerifier(verifier);
            client.start();

            ConnectFuture cf = client.connect(user, sshHost, sshPort);
            cf.await();
            try (ClientSession session = cf.getSession()) {
                for (KeyPair pair : provider.getKeys()) {
                    CLI.LOGGER.log(FINE, "Offering {0} private key", pair.getPrivate().getAlgorithm());
                    session.addPublicKeyIdentity(pair);
                }
                session.auth().verify(10000L);

                try (ClientChannel channel = session.createExecChannel(command.toString())) {
                    channel.setIn(new NoCloseInputStream(System.in));
                    channel.setOut(new NoCloseOutputStream(System.out));
                    channel.setErr(new NoCloseOutputStream(System.err));
                    WaitableFuture wf = channel.open();
                    wf.await();

                    Set<ClientChannelEvent> waitMask = channel.waitFor(Collections.singletonList(ClientChannelEvent.CLOSED), 0L);

                    if(waitMask.contains(ClientChannelEvent.TIMEOUT)) {
                        throw new SocketTimeoutException("Failed to retrieve command result in time: " + command);
                    }

                    Integer exitStatus = channel.getExitStatus();
                    return exitStatus;

                }
            } finally {
                client.stop();
            }
        }
    }

    private SSHCLI() {}

}
