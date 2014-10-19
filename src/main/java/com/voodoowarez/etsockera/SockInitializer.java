package com.voodoowarez.etsockera;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.ssl.SslContext;

import java.util.Iterator;

public class SockInitializer extends ChannelInitializer<SocketChannel> {

	final Iterator<SimpleChannelInboundHandler<?>> socketHandlers;
	final SslContext sslCtx;

	public SockInitializer(final Iterator<SimpleChannelInboundHandler<?>> socketHandlers, SslContext sslContext){
		this.socketHandlers = socketHandlers;
		this.sslCtx = sslContext;
	}

	@Override
	public void initChannel(SocketChannel ch) throws Exception {
			if(!this.socketHandlers.hasNext())
				throw new RuntimeException("No pipeline stages to build"); 

			ChannelPipeline pipeline = ch.pipeline();
			if (sslCtx != null) {
					pipeline.addLast(this.sslCtx.newHandler(ch.alloc()));
			}
			pipeline.addLast(new HttpServerCodec());
			pipeline.addLast(new HttpObjectAggregator(65536));
			pipeline.addLast(new WebSocketServerCompressionHandler());

			SimpleChannelInboundHandler<?> cursor;
			while(this.socketHandlers.hasNext()){
				pipeline.addLast(this.socketHandlers.next());
			}
	}
}
