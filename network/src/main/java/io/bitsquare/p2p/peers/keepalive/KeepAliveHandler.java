package io.bitsquare.p2p.peers.keepalive;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import io.bitsquare.app.Log;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.NodeAddress;
import io.bitsquare.p2p.network.CloseConnectionReason;
import io.bitsquare.p2p.network.Connection;
import io.bitsquare.p2p.network.MessageListener;
import io.bitsquare.p2p.network.NetworkNode;
import io.bitsquare.p2p.peers.PeerManager;
import io.bitsquare.p2p.peers.keepalive.messages.Ping;
import io.bitsquare.p2p.peers.keepalive.messages.Pong;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Random;

class KeepAliveHandler implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(KeepAliveHandler.class);

    @Nullable
    private Connection connection;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface Listener {
        void onComplete();

        void onFault(String errorMessage, Connection connection);

        void onFault(String errorMessage, NodeAddress nodeAddress);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final NetworkNode networkNode;
    private final PeerManager peerManager;
    private final Listener listener;
    private final int nonce = new Random().nextInt();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public KeepAliveHandler(NetworkNode networkNode, PeerManager peerManager, Listener listener) {
        this.networkNode = networkNode;
        this.peerManager = peerManager;
        this.listener = listener;
    }

    public void cleanup() {
        if (connection != null)
            connection.removeMessageListener(this);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////


    public void sendPing(Connection connection) {
        Log.traceCall("connection=" + connection + " / this=" + this);
        this.connection = connection;
        connection.addMessageListener(this);
        Ping ping = new Ping(nonce);
        SettableFuture<Connection> future = networkNode.sendMessage(connection, ping);
        Futures.addCallback(future, new FutureCallback<Connection>() {
            @Override
            public void onSuccess(Connection connection) {
                log.trace("Send " + ping + " to " + connection + " succeeded.");
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                String errorMessage = "Sending ping to " + connection +
                        " failed. That is expected if the peer is offline.\n\tping=" + ping +
                        ".\n\tException=" + throwable.getMessage();
                log.info(errorMessage);
                cleanup();
                peerManager.shutDownConnection(connection, CloseConnectionReason.SEND_MSG_FAILURE);
                listener.onFault(errorMessage, connection);
            }
        });
    }

    public void sendPing(NodeAddress nodeAddress) {
        Log.traceCall("nodeAddress=" + nodeAddress + " / this=" + this);
        Ping ping = new Ping(nonce);
        SettableFuture<Connection> future = networkNode.sendMessage(nodeAddress, ping);
        Futures.addCallback(future, new FutureCallback<Connection>() {
            @Override
            public void onSuccess(Connection connection) {
                if (connection != null) {
                    KeepAliveHandler.this.connection = connection;
                    connection.addMessageListener(KeepAliveHandler.this);
                }

                log.trace("Send " + ping + " to " + nodeAddress + " succeeded.");
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                String errorMessage = "Sending ping to " + nodeAddress +
                        " failed. That is expected if the peer is offline.\n\tping=" + ping +
                        ".\n\tException=" + throwable.getMessage();
                log.info(errorMessage);
                cleanup();
                peerManager.shutDownConnection(nodeAddress, CloseConnectionReason.SEND_MSG_FAILURE);
                listener.onFault(errorMessage, nodeAddress);
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(Message message, Connection connection) {
        if (message instanceof Pong) {
            Log.traceCall(message.toString() + "\n\tconnection=" + connection);
            Pong pong = (Pong) message;
            if (pong.requestNonce == nonce) {
                cleanup();
                listener.onComplete();
            } else {
                log.warn("Nonce not matching. That should never happen.\n\t" +
                                "We drop that message. nonce={} / requestNonce={}",
                        nonce, pong.requestNonce);
            }
        }
    }

}