package io.onedev.k8shelper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Uses the inherited unconnected socket behavior so HttpsURLConnection layers TLS over
 * the TCP connection and supplies the current hostname, including after redirects.
 */
final class SniSocketFactory extends SSLSocketFactory {

	private final SSLSocketFactory delegate;

	SniSocketFactory(SSLSocketFactory delegate) {
		this.delegate = delegate;
	}

	@Override
	public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
		return configure(delegate.createSocket(socket, host, port, autoClose), host);
	}

	@Override
	public Socket createSocket(String host, int port) throws IOException {
		return configure(delegate.createSocket(host, port), host);
	}

	@Override
	public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
		return configure(delegate.createSocket(host, port, localAddress, localPort), host);
	}

	@Override
	public Socket createSocket(InetAddress address, int port) throws IOException {
		return delegate.createSocket(address, port);
	}

	@Override
	public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
		return delegate.createSocket(address, port, localAddress, localPort);
	}

	@Override
	public String[] getDefaultCipherSuites() {
		return delegate.getDefaultCipherSuites();
	}

	@Override
	public String[] getSupportedCipherSuites() {
		return delegate.getSupportedCipherSuites();
	}

	private static Socket configure(Socket socket, String host) {
		var serverName = KubernetesHelper.getSingleLabelServerName(host);
		if (serverName != null) {
			var sslSocket = (SSLSocket) socket;
			var parameters = sslSocket.getSSLParameters();
			parameters.setServerNames(List.of(serverName));
			sslSocket.setSSLParameters(parameters);
		}
		return socket;
	}
}
