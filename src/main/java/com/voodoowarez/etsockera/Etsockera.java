package com.voodoowarez.etsockera;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.util.Arrays;
import java.util.Iterator;

/**
* A double-listening websocket relay
*/
public class Etsockera {

	private SslContext sslCtx1,
	                   sslCtx2;
	private SockHandler socket1,
	                    socket2;

	public Etsockera(SslContext sslCtx1, SslContext sslCtx2){
		this.sslCtx1 = sslCtx1;
		this.sslCtx2 = sslCtx2;
	}

	public Etsockera(SslContext sslCtx){
		this.sslCtx1 = sslCtx;
		this.sslCtx2 = sslCtx;
	}

	public SockHandler getSocket1 (){
		init();
		return this.socket1;
	}

	public SockHandler getSocket2(){
		init();
		return this.socket2;
	}

	public SockInitializer getInitializer1(){
		return this.getInitializer(true);
	}

	public SockInitializer getInitializer2(){
		return this.getInitializer(false);
	}
	
	protected SockInitializer getInitializer(boolean first){
		final SimpleChannelInboundHandler<?> handler = first ? this.getSocket1() : this.getSocket2();
		final SslContext sslCtx = first ? this.sslCtx1 : this.sslCtx2;
		
		final SimpleChannelInboundHandler<?> handlers[] = new SimpleChannelInboundHandler[]{ handler };
		final Iterator<SimpleChannelInboundHandler<?>> handlerIter = Arrays.asList(handlers).listIterator();
		final SockInitializer initializer = new SockInitializer(handlerIter, sslCtx);
		return initializer;
	}

	private void init(){
		if(this.socket1 != null)
			return;
		this.socket1 = new SockHandler("port", this.sslCtx1 != null, null);
		this.socket2 = new SockHandler("port", this.sslCtx2 != null, this.socket1.getOpenContextSupplier());
		this.socket1.setForwardingContextSupplier(this.socket2.getOpenContextSupplier());
	}

	public static void main(String[] args) throws Exception {
		final boolean SSL = System.getProperty("ssl") != null;
		final int PORT = Integer.parseInt(System.getProperty("port", "7400"));
		final SslContext sslCtx;

		if (SSL) {
			SelfSignedCertificate ssc = new SelfSignedCertificate();
			sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
		} else {
			sslCtx = null;
		}

		Etsockera etsockera = new Etsockera(sslCtx);
		SockInitializer initializer1 = etsockera.getInitializer1(),
		                initializer2 = etsockera.getInitializer1();

		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup workerGroup = new NioEventLoopGroup();
		try {
			
			ServerBootstrap server1 = (new ServerBootstrap())
			 .group(bossGroup, workerGroup)
			 .channel(NioServerSocketChannel.class)
			 .handler(new LoggingHandler(LogLevel.INFO))
			 .childHandler(initializer1);
			Channel channel1 = server1.bind(PORT).sync().channel();

			ServerBootstrap server2 = (new ServerBootstrap())
			 .group(bossGroup, workerGroup)
			 .channel(NioServerSocketChannel.class)
			 .handler(new LoggingHandler(LogLevel.INFO))
			 .childHandler(initializer2);
			Channel channel2 = server2.bind(PORT+1).sync().channel();

			channel1.closeFuture().sync();
			channel2.closeFuture().sync();
		} finally {
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
		}
	}
}