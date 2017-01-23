package io.bitsquare.p2p.network;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.berndpruenster.jtor.mgmt.TorManager;
import org.berndpruenster.jtor.socket.HiddenServiceSocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;

import io.bitsquare.app.Log;
import io.bitsquare.common.UserThread;
import io.bitsquare.common.util.Utilities;
import io.bitsquare.p2p.NodeAddress;

// Run in UserThread
public class LocalhostNetworkNode extends NetworkNode {
    private static final Logger log = LoggerFactory.getLogger(LocalhostNetworkNode.class);

    private static volatile int simulateTorDelayTorNode = 500;
    private static volatile int simulateTorDelayHiddenService = 500;

    public static void setSimulateTorDelayTorNode(int simulateTorDelayTorNode) {
        LocalhostNetworkNode.simulateTorDelayTorNode = simulateTorDelayTorNode;
    }

    public static void setSimulateTorDelayHiddenService(int simulateTorDelayHiddenService) {
        LocalhostNetworkNode.simulateTorDelayHiddenService = simulateTorDelayHiddenService;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public LocalhostNetworkNode(int port) {
        super(port);
    }

    @Override
    public void start(@Nullable SetupListener setupListener) {
        if (setupListener != null)
            addSetupListener(setupListener);

        createExecutorService();

        //Tor delay simulation
        createTorNode(torNode -> {
            Log.traceCall("torNode created");
            setupListeners.stream().forEach(SetupListener::onTorNodeReady);

            // Create Hidden Service (takes about 40 sec.)
            createHiddenService(hiddenServiceDescriptor -> {
                Log.traceCall("hiddenService created");
                try {
                    startServer(new ServerSocket(servicePort));
                } catch (IOException e) {
                    e.printStackTrace();
                    log.error("Exception at startServer: " + e.getMessage());
                }

                nodeAddressProperty.set(new NodeAddress("localhost", servicePort));
                setupListeners.stream().forEach(SetupListener::onHiddenServicePublished);
            });
        });
    }

    // Called from NetworkNode thread
    @Override
    protected Socket createSocket(NodeAddress peerNodeAddress) throws IOException {
        return new Socket(peerNodeAddress.hostName, peerNodeAddress.port);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Tor delay simulation
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void createTorNode(final Consumer<TorManager> resultHandler) {
        ListenableFuture<TorManager> future = executorService.submit(() -> {
            Utilities.setThreadName("NetworkNode:CreateTorNode");
            long ts = System.currentTimeMillis();
            if (simulateTorDelayTorNode > 0)
                Uninterruptibles.sleepUninterruptibly(simulateTorDelayTorNode, TimeUnit.MILLISECONDS);
            log.debug("\n\n############################################################\n" +
                    "TorNode created [simulation]:" +
                    "\nTook " + (System.currentTimeMillis() - ts) + " ms"
                    + "\n############################################################\n");
            return null;
        });
        Futures.addCallback(future, new FutureCallback<TorManager>() {
            public void onSuccess(TorManager torMgr) {
                UserThread.execute(() -> {
                    // as we are simulating we return null
                    resultHandler.accept(null);
                });
            }

            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> {
                    log.error("[simulation] TorNode creation failed. " + throwable.getMessage());
                    throwable.printStackTrace();
                });
            }
        });
    }

    private void createHiddenService(final Consumer<HiddenServiceSocket> resultHandler) {
        ListenableFuture<HiddenServiceSocket> future = executorService.submit(() -> {
            Utilities.setThreadName("NetworkNode:CreateHiddenService");
            long ts = System.currentTimeMillis();
            if (simulateTorDelayHiddenService > 0)
                Uninterruptibles.sleepUninterruptibly(simulateTorDelayHiddenService, TimeUnit.MILLISECONDS);
            log.debug("\n\n############################################################\n" +
                    "Hidden service published [simulation]:" +
                    "\nTook " + (System.currentTimeMillis() - ts) + " ms"
                    + "\n############################################################\n");
            return null;
        });
        Futures.addCallback(future, new FutureCallback<HiddenServiceSocket>() {
            public void onSuccess(HiddenServiceSocket hiddenServiceSocket) {
                UserThread.execute(() -> {
                    // as we are simulating we return null
                    resultHandler.accept(null);
                });
            }

            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> {
                    log.error("[simulation] Hidden service creation failed. " + throwable.getMessage());
                    throwable.printStackTrace();
                });
            }
        });
    }
}
