/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.apache.sshd.client.auth.UserAuthKeyboardInteractive;
import org.apache.sshd.client.auth.UserAuthPassword;
import org.apache.sshd.client.auth.UserAuthPublicKey;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.config.keys.ClientIdentity;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.future.DefaultConnectFuture;
import org.apache.sshd.client.session.ClientConnectionService;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSessionImpl;
import org.apache.sshd.client.session.ClientUserAuthService;
import org.apache.sshd.common.AbstractFactoryManager;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.Factory;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.SshdSocketAddress;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.config.SshConfigFileReader;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.io.IoConnectFuture;
import org.apache.sshd.common.io.IoConnector;
import org.apache.sshd.common.session.AbstractSession;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.SecurityUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.io.NoCloseInputStream;
import org.apache.sshd.common.util.io.NoCloseOutputStream;

/**
 * Entry point for the client side of the SSH protocol.
 *
 * The default configured client can be created using
 * the {@link #setUpDefaultClient()}.  The next step is to
 * start the client using the {@link #start()} method.
 *
 * Sessions can then be created using on of the
 * {@link #connect(String, String, int)} or {@link #connect(String, java.net.SocketAddress)}
 * methods.
 *
 * The client can be stopped at anytime using the {@link #stop()} method.
 *
 * Following is an example of using the SshClient:
 * <pre>
 *    SshClient client = SshClient.setUpDefaultClient();
 *    client.start();
 *    try {
 *        ClientSession session = client.connect(login, host, port).await().getSession();
 *        session.addPasswordIdentity(password);
 *        session.auth().verify();
 *
 *        ClientChannel channel = session.createChannel("shell");
 *        channel.setIn(new NoCloseInputStream(System.in));
 *        channel.setOut(new NoCloseOutputStream(System.out));
 *        channel.setErr(new NoCloseOutputStream(System.err));
 *        channel.open();
 *        channel.waitFor(ClientChannel.CLOSED, 0);
 *        session.close(false);
 *    } finally {
 *        client.stop();
 *    }
 * </pre>
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class SshClient extends AbstractFactoryManager implements ClientFactoryManager, Closeable {

    public static final Factory<SshClient> DEFAULT_SSH_CLIENT_FACTORY = new Factory<SshClient>() {
        @Override
        public SshClient create() {
            return new SshClient();
        }
    };

    protected IoConnector connector;
    protected SessionFactory sessionFactory;
    protected UserInteraction userInteraction;
    protected List<NamedFactory<UserAuth>> userAuthFactories;

    private ServerKeyVerifier serverKeyVerifier;

    public SshClient() {
        super();
    }

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public ServerKeyVerifier getServerKeyVerifier() {
        return serverKeyVerifier;
    }

    public void setServerKeyVerifier(ServerKeyVerifier serverKeyVerifier) {
        this.serverKeyVerifier = serverKeyVerifier;
    }

    @Override
    public UserInteraction getUserInteraction() {
        return userInteraction;
    }

    public void setUserInteraction(UserInteraction userInteraction) {
        this.userInteraction = userInteraction;
    }

    @Override
    public List<NamedFactory<UserAuth>> getUserAuthFactories() {
        return userAuthFactories;
    }

    public void setUserAuthFactories(List<NamedFactory<UserAuth>> userAuthFactories) {
        this.userAuthFactories = userAuthFactories;
    }

    @Override
    protected void checkConfig() {
        super.checkConfig();
        
        ValidateUtils.checkNotNull(getTcpipForwarderFactory(), "TcpipForwarderFactory not set", GenericUtils.EMPTY_OBJECT_ARRAY);
        ValidateUtils.checkNotNull(getServerKeyVerifier(), "ServerKeyVerifier not set", GenericUtils.EMPTY_OBJECT_ARRAY);

        // Register the additional agent forwarding channel if needed
        if (getAgentFactory() != null) {
            List<NamedFactory<Channel>> factories = getChannelFactories();
            if (GenericUtils.isEmpty(factories)) {
                factories = new ArrayList<NamedFactory<Channel>>();
            } else {
                factories = new ArrayList<NamedFactory<Channel>>(factories);
            }
            factories.add(getAgentFactory().getChannelForwardingFactory());
            setChannelFactories(factories);
        }

        if (GenericUtils.isEmpty(getServiceFactories())) {
            setServiceFactories(Arrays.asList(
                    new ClientUserAuthService.Factory(),
                    new ClientConnectionService.Factory()
            ));
        }

        if (GenericUtils.isEmpty(getUserAuthFactories())) {
            setUserAuthFactories(Arrays.asList(
                    UserAuthPublicKey.UserAuthPublicKeyFactory.INSTANCE,
                    UserAuthKeyboardInteractive.UserAuthKeyboardInteractiveFactory.INSTANCE,
                    UserAuthPassword.UserAuthPasswordFactory.INSTANCE
            ));
        }
    }

    public void start() {
        checkConfig();
        if (sessionFactory == null) {
            sessionFactory = createSessionFactory();
        }

        setupSessionTimeout(sessionFactory);

        sessionFactory.setClient(this);
        connector = createConnector();
    }

    public void stop() {
        try {
            close(true).await();    // TODO use verify + configurable timeout
        } catch (IOException e) {
            log.debug("Exception caught while stopping client", e);
        }
    }

    public void open() throws IOException {
        start();
    }

    @Override
    protected Closeable getInnerCloseable() {
        return builder()
                .run(new Runnable() {
                    @SuppressWarnings("synthetic-access")
                    @Override
                    public void run() {
                        removeSessionTimeout(sessionFactory);
                    }
                })
                .sequential(connector, ioServiceFactory)
                .run(new Runnable() {
                    @SuppressWarnings("synthetic-access")
                    @Override
                    public void run() {
                        connector = null;
                        ioServiceFactory = null;
                        if (shutdownExecutor && (executor != null) && (!executor.isShutdown())) {
                            try {
                                executor.shutdownNow();
                            } finally {
                                executor = null;
                            }
                        }
                    }
                })
                .build();
    }

    public ConnectFuture connect(String username, String host, int port) throws IOException {
        assert host != null;
        assert port >= 0;
        if (connector == null) {
            throw new IllegalStateException("SshClient not started. Please call start() method before connecting to a server");
        }
        SocketAddress address = new InetSocketAddress(host, port);
        return connect(username, address);
    }

    public ConnectFuture connect(final String username, SocketAddress address) {
        assert address != null;
        if (connector == null) {
            throw new IllegalStateException("SshClient not started. Please call start() method before connecting to a server");
        }
        final ConnectFuture connectFuture = new DefaultConnectFuture(null);
        connector.connect(address).addListener(new SshFutureListener<IoConnectFuture>() {
            @Override
            public void operationComplete(IoConnectFuture future) {
                if (future.isCanceled()) {
                    connectFuture.cancel();
                } else if (future.getException() != null) {
                    connectFuture.setException(future.getException());
                } else {
                    ClientSessionImpl session = (ClientSessionImpl) AbstractSession.getSession(future.getSession());
                    session.setUsername(username);
                    connectFuture.setSession(session);
                }
            }
        });
        return connectFuture;
    }

    protected IoConnector createConnector() {
        return getIoServiceFactory().createConnector(getSessionFactory());
    }

    protected SessionFactory createSessionFactory() {
        return new SessionFactory();
    }

    @Override
    public String toString() {
        return "SshClient[" + Integer.toHexString(hashCode()) + "]";
    }

    /**
     * Setup a default client.  The client does not require any additional setup.
     *
     * @return a newly create SSH client
     */
    public static SshClient setUpDefaultClient() {
        return ClientBuilder.builder().build();
    }

    /*=================================
          Main class implementation
     *=================================*/

    public static void main(String[] args) throws Exception {
        Handler fh = new ConsoleHandler();
        fh.setLevel(Level.FINEST);
        fh.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                String message = formatMessage(record);
                String throwable = "";
                if (record.getThrown() != null) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println();
                    record.getThrown().printStackTrace(pw);
                    pw.close();
                    throwable = sw.toString();
                }
                return String.format("%1$tY-%1$tm-%1$td: %2$-7.7s: %3$-32.32s: %4$s%5$s%n",
                        new Date(record.getMillis()),
                        record.getLevel().getName(),
                        record.getLoggerName(),
                        message,
                        throwable);
            }
        });
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        root.addHandler(fh);

        int port = SshConfigFileReader.DEFAULT_PORT;
        String host = null;
        String login = System.getProperty("user.name");
        boolean agentForward = false;
        List<String> command = null;
        int logLevel = 0;
        int socksPort = -1;
        boolean error = false;
        List<String> identities = new ArrayList<String>();
        Map<String, String> options = new LinkedHashMap<String, String>();

        for (int i = 0; i < args.length; i++) {
            if (command == null && "-p".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("option requires an argument: " + args[i]);
                    error = true;
                    break;
                }
                port = Integer.parseInt(args[++i]);
            } else if (command == null && "-D".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("option requires an argument: " + args[i]);
                    error = true;
                    break;
                }
                socksPort = Integer.parseInt(args[++i]);
            } else if (command == null && "-l".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("option requires an argument: " + args[i]);
                    error = true;
                    break;
                }
                login = args[++i];
            } else if (command == null && "-v".equals(args[i])) {
                logLevel += 1;
            } else if (command == null && "-vv".equals(args[i])) {
                logLevel += 2;
            } else if (command == null && "-vvv".equals(args[i])) {
                logLevel += 3;
            } else if (command == null && "-A".equals(args[i])) {
                agentForward = true;
            } else if (command == null && "-a".equals(args[i])) {
                agentForward = false;
            } else if (command == null && "-i".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("option requires and argument: " + args[i]);
                    error = true;
                    break;
                }
                identities.add(args[++i]);
            } else if (command == null && "-o".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("option requires and argument: " + args[i]);
                    error = true;
                    break;
                }
                String opt = args[++i];
                int idx = opt.indexOf('=');
                if (idx <= 0) {
                    System.err.println("bad syntax for option: " + opt);
                    error = true;
                    break;
                }
                options.put(opt.substring(0, idx), opt.substring(idx + 1));
            } else if (command == null && args[i].startsWith("-")) {
                System.err.println("illegal option: " + args[i]);
                error = true;
                break;
            } else {
                if (command == null && host == null) {
                    host = args[i];
                } else {
                    if (command == null) {
                        command = new ArrayList<String>();
                    }
                    command.add(args[i]);
                }
            }
        }
        if (host == null) {
            System.err.println("hostname required");
            error = true;
        }
        if (error) {
            System.err.println("usage: ssh [-A|-a] [-v[v][v]] [-D socksPort] [-l login] [-p port] [-o option=value] hostname [command]");
            System.exit(-1);
        }
        if (logLevel <= 0) {
            root.setLevel(Level.WARNING);
        } else if (logLevel == 1) {
            root.setLevel(Level.INFO);
        } else if (logLevel == 2) {
            root.setLevel(Level.FINE);
        } else {
            root.setLevel(Level.FINEST);
        }

        try(SshClient client = SshClient.setUpDefaultClient();
            BufferedReader stdin = new BufferedReader(new InputStreamReader(new NoCloseInputStream(System.in)))) {
            if (SecurityUtils.isBouncyCastleRegistered()) {
                try {
                    ClientIdentity.setKeyPairProvider(client,
                            false,  // not strict - even though we should...
                            true,   // supportedOnly
                            new FilePasswordProvider() {
                                @Override
                                public String getPassword(String file) throws IOException {
                                    System.out.print("Enter password for private key file=" + file + ": ");
                                    return stdin.readLine();
                                }
                            });
                } catch (Throwable t) {
                    System.out.println("Error loading user keys: " + t.getMessage());
                }
            }
    
            Map<String,Object> props = client.getProperties();
            props.putAll(options);
    
            client.start();
            client.setUserInteraction(new UserInteraction() {
                    @Override
                    public void welcome(String banner) {
                        System.out.println(banner);
                    }
        
                    @Override
                    public String[] interactive(String destination, String name, String instruction, String[] prompt, boolean[] echo) {
                        String[] answers = new String[prompt.length];
                        try {
                            for (int i = 0; i < prompt.length; i++) {
                                System.out.print(prompt[i] + " ");
                                answers[i] = stdin.readLine();
                            }
                        } catch (IOException e) {
                            // ignored
                        }
                        return answers;
                    }
                });

            /*
            String authSock = System.getenv(SshAgent.SSH_AUTHSOCKET_ENV_NAME);
            if (authSock == null && provider != null) {
                Iterable<KeyPair> keys = provider.loadKeys();
                AgentServer server = new AgentServer();
                authSock = server.start();
                SshAgent agent = new AgentClient(authSock);
                for (KeyPair key : keys) {
                    agent.addIdentity(key, "");
                }
                agent.close();
                props.put(SshAgent.SSH_AUTHSOCKET_ENV_NAME, authSock);
            }
            */
    
            try(ClientSession session = client.connect(login, host, port).await().getSession()) {
                session.auth().verify();
        
                if (socksPort >= 0) {
                    session.startDynamicPortForwarding(new SshdSocketAddress("localhost", socksPort));
                    Thread.sleep(Long.MAX_VALUE);
                } else {
                    ClientChannel channel;
                    if (command == null) {
                        channel = session.createChannel(ClientChannel.CHANNEL_SHELL);
                        ((ChannelShell) channel).setAgentForwarding(agentForward);
                        channel.setIn(new NoCloseInputStream(System.in));
                    } else {
                        StringWriter w = new StringWriter();
                        for (String cmd : command) {
                            w.append(cmd).append(" ");
                        }
                        w.close();
                        channel = session.createChannel(ClientChannel.CHANNEL_EXEC, w.toString());
                    }
                    
                    try {
                        channel.setOut(new NoCloseOutputStream(System.out));
                        channel.setErr(new NoCloseOutputStream(System.err));
                        channel.open().await();
                        channel.waitFor(ClientChannel.CLOSED, 0);
                    } finally {
                        channel.close();
                    }
                    session.close(false);
                }
            } finally {
                client.stop();
            }
        }
    }
}