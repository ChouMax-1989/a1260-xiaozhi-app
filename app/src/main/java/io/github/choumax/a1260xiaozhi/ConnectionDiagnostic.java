package io.github.choumax.a1260xiaozhi;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;

/** Immutable allow-listed diagnostics. Never retains an exception, URL, headers, close reason or payload. */
public final class ConnectionDiagnostic {
    public enum Reason {
        CONNECTED, USER_DISCONNECTED, NORMAL_IDLE_CLOSE, SESSION_INTERRUPTED, REMOTE_CLOSE,
        DNS_FAILURE, TLS_FAILURE, SOCKET_TIMEOUT, PING_TIMEOUT, CONNECT_FAILURE, SOCKET_FAILURE,
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_REJECTED, PROTOCOL_FAILURE, HELLO_TIMEOUT,
        SEND_FAILURE, RECEIVE_OVERFLOW, LOCAL_FAILURE, CONFIG_INVALID, UNKNOWN_FAILURE
    }
    /** Hints only: the owning service decides timing, online gating and bounded retry count. */
    public enum Recovery { NONE, ON_DEMAND, RETRY_BACKOFF, USER_ACTION }
    public final Reason reason;
    public final Recovery recovery;
    public final SessionState previousState, nextState;
    public final int httpStatus, webSocketCloseCode;
    public final long timestampMillis;
    public final boolean isError;
    public final String summary;

    private ConnectionDiagnostic(Reason reason, Recovery recovery, SessionState previous, SessionState next,
                                 int http, int close, boolean error) {
        this.reason = reason; this.recovery = recovery; previousState = previous; nextState = next;
        httpStatus = http >= 100 && http <= 599 ? http : 0;
        webSocketCloseCode = close >= 1000 && close <= 4999 ? close : 0;
        timestampMillis = System.currentTimeMillis(); isError = error;
        summary = reason.name() + "：" + message(reason)
                + (httpStatus == 0 ? "" : "（HTTP " + httpStatus + "）")
                + (webSocketCloseCode == 0 ? "" : "（WS " + webSocketCloseCode + "）");
    }
    public static ConnectionDiagnostic event(Reason reason, SessionState state) {
        if (reason == Reason.CONNECTED) return new ConnectionDiagnostic(reason, Recovery.NONE, state, SessionState.READY, 0, 0, false);
        if (reason == Reason.USER_DISCONNECTED) return new ConnectionDiagnostic(reason, Recovery.NONE, state, SessionState.DISCONNECTED, 0, 0, false);
        Recovery recovery = reason == Reason.HELLO_TIMEOUT || reason == Reason.SEND_FAILURE ? Recovery.RETRY_BACKOFF
                : reason == Reason.CONFIG_INVALID || reason == Reason.PROTOCOL_FAILURE ? Recovery.USER_ACTION : Recovery.NONE;
        return new ConnectionDiagnostic(reason, recovery, state, SessionState.ERROR, 0, 0, true);
    }
    public static ConnectionDiagnostic failure(Throwable error, int http, SessionState state) {
        if (http != 0 && http != 101) {
            Reason reason = http == 401 ? Reason.HTTP_UNAUTHORIZED : http == 403 ? Reason.HTTP_FORBIDDEN : Reason.HTTP_REJECTED;
            Recovery recovery = http == 408 || http == 429 || http >= 500 ? Recovery.RETRY_BACKOFF : Recovery.USER_ACTION;
            return new ConnectionDiagnostic(reason, recovery, state, SessionState.ERROR, http, 0, true);
        }
        boolean dns = false, tls = false, timeout = false, ping = false, connect = false, socket = false, protocol = false, io = false;
        // Bound traversal even for malicious or cyclic causes. Message matching never enters the output.
        Throwable cause = error;
        for (int i = 0; cause != null && i < 12; i++, cause = cause.getCause()) {
            dns |= cause instanceof UnknownHostException;
            tls |= cause instanceof SSLException || cause instanceof CertificateException;
            timeout |= cause instanceof SocketTimeoutException;
            if (cause instanceof SocketTimeoutException) {
                String message = cause.getMessage();
                ping |= message != null && message.contains("sent ping but didn't receive pong within");
            }
            connect |= cause instanceof ConnectException || cause instanceof NoRouteToHostException;
            protocol |= cause instanceof ProtocolException;
            socket |= cause instanceof SocketException || cause instanceof EOFException;
            io |= cause instanceof IOException;
        }
        Reason reason = dns ? Reason.DNS_FAILURE : tls ? Reason.TLS_FAILURE : ping ? Reason.PING_TIMEOUT
                : timeout ? Reason.SOCKET_TIMEOUT : connect ? Reason.CONNECT_FAILURE
                : protocol ? Reason.PROTOCOL_FAILURE : socket ? Reason.SOCKET_FAILURE
                : io ? Reason.SOCKET_FAILURE : Reason.UNKNOWN_FAILURE;
        Recovery recovery = reason == Reason.TLS_FAILURE || reason == Reason.PROTOCOL_FAILURE ? Recovery.USER_ACTION
                : reason == Reason.UNKNOWN_FAILURE ? Recovery.NONE : Recovery.RETRY_BACKOFF;
        return new ConnectionDiagnostic(reason, recovery, state, SessionState.ERROR, http, 0, true);
    }
    public static ConnectionDiagnostic remoteClose(int code, SessionState state) {
        if (code == 1000 || code == 1001 || code == 1005) {
            // The close frame establishes normal closure; idle timeout is only a possible server policy.
            boolean idle = state == SessionState.READY;
            return new ConnectionDiagnostic(idle ? Reason.NORMAL_IDLE_CLOSE : Reason.SESSION_INTERRUPTED,
                    Recovery.ON_DEMAND, state, SessionState.DISCONNECTED, 0, code, false);
        }
        boolean policy = code == 1002 || code == 1003 || code == 1007 || code == 1008 || code == 1009 || code == 1010;
        return new ConnectionDiagnostic(Reason.REMOTE_CLOSE, policy ? Recovery.USER_ACTION : Recovery.RETRY_BACKOFF,
                state, SessionState.ERROR, 0, code, true);
    }
    private static String message(Reason reason) {
        switch (reason) {
            case CONNECTED: return "已完成服务器握手";
            case USER_DISCONNECTED: return "连接已由本机停止";
            case NORMAL_IDLE_CLOSE: return "服务器正常关闭空闲连接，下次使用时重新连接";
            case SESSION_INTERRUPTED: return "服务器正常关闭连接，本次会话已结束";
            case REMOTE_CLOSE: return "服务器关闭了连接，请按关闭码检查服务状态";
            case DNS_FAILURE: return "服务器域名解析失败，请检查DNS或网络";
            case TLS_FAILURE: return "TLS安全连接验证失败，请检查系统时间、证书及服务配置";
            case SOCKET_TIMEOUT: return "网络连接或读取超时";
            case PING_TIMEOUT: return "WebSocket心跳未收到pong；这不能单独证明Wi-Fi故障";
            case CONNECT_FAILURE: return "无法建立到服务器的连接";
            case SOCKET_FAILURE: return "连接异常中断，尚不能确定是网络还是服务端原因";
            case HTTP_UNAUTHORIZED: return "服务器未接受凭据，请检查设备绑定或重新获取配置";
            case HTTP_FORBIDDEN: return "服务器拒绝访问，请检查账号与设备授权";
            case HTTP_REJECTED: return "WebSocket升级请求未被服务器接受";
            case PROTOCOL_FAILURE: return "服务器握手或协议不兼容";
            case HELLO_TIMEOUT: return "WebSocket已打开，但未及时收到小智hello响应";
            case SEND_FAILURE: return "消息未能入队发送，连接已不可用或发送队列已满";
            case RECEIVE_OVERFLOW: return "服务器消息超过客户端有界接收预算";
            case CONFIG_INVALID: return "连接地址或身份配置无效，请检查设置";
            case LOCAL_FAILURE: return "本机音频或会话处理失败，请检查本机状态";
            default: return "未分类连接异常，需要进一步诊断";
        }
    }
    @Override public String toString() { return summary; }
}
