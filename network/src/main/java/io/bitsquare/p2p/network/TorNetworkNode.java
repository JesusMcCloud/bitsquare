package io.bitsquare.p2p.network;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.berndpruenster.jtor.mgmt.DesktopTorManager;
import org.berndpruenster.jtor.mgmt.TorCtlException;
import org.berndpruenster.jtor.mgmt.TorManager;
import org.berndpruenster.jtor.socket.HiddenServiceSocket;
import org.berndpruenster.jtor.socket.TorSocket;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.monadic.MonadicBinding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import io.bitsquare.app.Log;
import io.bitsquare.common.Timer;
import io.bitsquare.common.UserThread;
import io.bitsquare.common.util.Utilities;
import io.bitsquare.p2p.NodeAddress;
import io.bitsquare.p2p.Utils;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

// Run in UserThread
public class TorNetworkNode extends NetworkNode {
    public static final String DIR_HIDDENSERVICE = "hiddenservice";

    private static final Logger log = LoggerFactory.getLogger(TorNetworkNode.class);

    private static final int MAX_RESTART_ATTEMPTS = 5;
    private static final long SHUT_DOWN_TIMEOUT_SEC = 5;

    private final File torDir;
    private TorManager torMgr;
    private Timer shutDownTimeoutTimer;
    private int restartCounter;
    private MonadicBinding<Boolean> allShutDown;

    private final Collection<String> bridgeLines;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TorNetworkNode(int servicePort, File torDir, @Nullable Collection<String> bridgeLines) {
        super(servicePort);
        this.torDir = torDir;
        this.bridgeLines=bridgeLines;
    }

    public TorNetworkNode(int servicePort, File torDir) {
      this(servicePort, torDir, null);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void start(@Nullable SetupListener setupListener) {
        if (setupListener != null)
            addSetupListener(setupListener);

        createExecutorService();

        // Create the tor node (takes about 6 sec.)
        createTorManager(torDir, bridgeLines,
                torManager -> {
                    Log.traceCall("torNode created");
                    TorNetworkNode.this.torMgr = torManager;

                    setupListeners.stream().forEach(SetupListener::onTorNodeReady);

                    // Create Hidden Service (takes about 40 sec.)
                    createHiddenService(torManager,
                            Utils.findFreeSystemPort(),
                            servicePort,
                            hiddenServicesocket -> {
                                Log.traceCall("hiddenService created");
//                                TorNetworkNode.this.hiddenServiceDescriptor = hiddenServicesocket;
                                nodeAddressProperty.set(new NodeAddress(hiddenServicesocket.getServiceName(), hiddenServicesocket.getHiddenServicePort()));
                                startServer(hiddenServicesocket);
                                setupListeners.stream().forEach(SetupListener::onHiddenServicePublished);
                            });
                });
    }

    @Override
    protected Socket createSocket(NodeAddress peerNodeAddress) throws IOException {
        checkArgument(peerNodeAddress.hostName.endsWith(".onion"), "PeerAddress is not an onion address");
        return new TorSocket(torMgr, peerNodeAddress.hostName, peerNodeAddress.port);
    }

    @Override
    public Socks5Proxy getSocksProxy() {
        try {
          return torMgr != null ? torMgr.getProxy(null) : null;
        } catch (TorCtlException e) {
          log.warn(e.getMessage());
          return null;
        }
    }

    public void shutDown(@Nullable Runnable shutDownCompleteHandler) {
        Log.traceCall();
        BooleanProperty torNetworkNodeShutDown = torNetworkNodeShutDown();
        BooleanProperty networkNodeShutDown = networkNodeShutDown();
        BooleanProperty shutDownTimerTriggered = shutDownTimerTriggered();

        // Need to store allShutDown to not get garbage collected
        allShutDown = EasyBind.combine(torNetworkNodeShutDown, networkNodeShutDown, shutDownTimerTriggered, (a, b, c) -> (a && b) || c);
        allShutDown.subscribe((observable, oldValue, newValue) -> {
            if (newValue) {
                shutDownTimeoutTimer.stop();
                long ts = System.currentTimeMillis();
                log.debug("Shutdown executorService");
                try {
                    MoreExecutors.shutdownAndAwaitTermination(executorService, 500, TimeUnit.MILLISECONDS);
                    log.debug("Shutdown executorService done after " + (System.currentTimeMillis() - ts) + " ms.");
                    log.debug("Shutdown completed");
                } catch (Throwable t) {
                    log.error("Shutdown executorService failed with exception: " + t.getMessage());
                    t.printStackTrace();
                } finally {
                    if (shutDownCompleteHandler != null)
                        shutDownCompleteHandler.run();
                }
            }
        });
    }

    private BooleanProperty torNetworkNodeShutDown() {
        final BooleanProperty done = new SimpleBooleanProperty();
        executorService.submit(() -> {
            Utilities.setThreadName("torNetworkNodeShutDown");
            long ts = System.currentTimeMillis();
            log.debug("Shutdown torMgr");
            try {
                if (torMgr != null)
                    torMgr.shutdown();
                log.debug("Shutdown torMgr done after " + (System.currentTimeMillis() - ts) + " ms.");
            } catch (Throwable e) {
                log.error("Shutdown torMgr failed with exception: " + e.getMessage());
                e.printStackTrace();
            } finally {
                UserThread.execute(() -> done.set(true));
            }
        });
        return done;
    }

    private BooleanProperty networkNodeShutDown() {
        final BooleanProperty done = new SimpleBooleanProperty();
        super.shutDown(() -> done.set(true));
        return done;
    }

    private BooleanProperty shutDownTimerTriggered() {
        final BooleanProperty done = new SimpleBooleanProperty();
        shutDownTimeoutTimer = UserThread.runAfter(() -> {
            log.error("A timeout occurred at shutDown");
            done.set(true);
        }, SHUT_DOWN_TIMEOUT_SEC);
        return done;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // shutdown, restart
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void restartTor(String errorMessage) {
        Log.traceCall();
        restartCounter++;
        if (restartCounter > MAX_RESTART_ATTEMPTS) {
            String msg = "We tried to restart Tor " + restartCounter +
                    " times, but it continued to fail with error message:\n" +
                    errorMessage + "\n\n" +
                    "Please check your internet connection and firewall and try to start again.";
            log.error(msg);
            throw new RuntimeException(msg);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // create tor
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void createTorManager(final File torDir, @Nullable final Collection<String> bridgeLines, final Consumer<TorManager> resultHandler) {
        Log.traceCall();
        ListenableFuture<TorManager> future = executorService.submit(() -> {
            Utilities.setThreadName("TorNetworkNode:CreateTorNode");
            long ts = System.currentTimeMillis();
            if (torDir.mkdirs())
                log.trace("Created directory for tor at {}", torDir.getAbsolutePath());
            
            TorManager torMgr = new DesktopTorManager(torDir,bridgeLines);
            log.debug("\n\n############################################################\n" +
                    "TorManager created:" +
                    "\nTook " + (System.currentTimeMillis() - ts) + " ms"
                    + "\n############################################################\n");
            return torMgr;
        });
        Futures.addCallback(future, new FutureCallback<TorManager>() {
            public void onSuccess(TorManager torMgr) {
                UserThread.execute(() -> resultHandler.accept(torMgr));
            }

            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> {
                    log.error("TorNode creation failed with exception: " + throwable.getMessage());
                    restartTor(throwable.getMessage());
                });
            }
        });
    }

    private void createHiddenService(TorManager torMgr, int localPort, int servicePort,
                                     Consumer<HiddenServiceSocket> resultHandler) {
        Log.traceCall();
        ListenableFuture<Object> future = executorService.submit(() -> {
            Utilities.setThreadName("TorNetworkNode:CreateHiddenService");
            {
                long ts = System.currentTimeMillis();
                //TODO do not put a hidden service into the hidden service root
                HiddenServiceSocket hiddenServiceSocket = new HiddenServiceSocket(torMgr, localPort, servicePort, ".");
                hiddenServiceSocket.addReadyListener( descriptor -> {
                    log.debug("\n\n############################################################\n" +
                            "Hidden service published:" +
                            "\nAddress=" + descriptor.toString() +
                            "\nTook " + (System.currentTimeMillis() - ts) + " ms"
                            + "\n############################################################\n");

                    UserThread.execute(() -> resultHandler.accept(hiddenServiceSocket));
                });
                return null;
            }
        });
        Futures.addCallback(future, new FutureCallback<Object>() {
            public void onSuccess(Object hiddenServicesocket) {
                log.debug("HiddenServiceDescriptor created. Wait for publishing.");
            }

            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> {
                    log.error("Hidden service creation failed");
                    restartTor(throwable.getMessage());
                });
            }
        });
    }
}
