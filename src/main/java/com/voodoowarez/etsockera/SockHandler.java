package com.voodoowarez.etsockera;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderUtil;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Handles socket traffic, forwarding traffic to supplied ChannelHandlerContext's.
 */
public class SockHandler extends SimpleChannelInboundHandler<Object> {

	public interface ContextSupplier extends Supplier<Iterator<ChannelHandlerContext>>{}

	private WebSocketServerHandshaker handshaker;
	final private String path;
	final boolean ssl;
	private ContextSupplier forwardContextSupplier,
	                        openContextSupplier;
	final private List<ChannelHandlerContext> openContexts;

	public SockHandler(final String path, boolean ssl, final ContextSupplier forwardContextSupplier){
		this.path = path;
		this.ssl = ssl;
		this.forwardContextSupplier = forwardContextSupplier;
		final List<ChannelHandlerContext> openContexts = this.openContexts = new ArrayList<ChannelHandlerContext>();
		this.openContextSupplier = () -> openContexts.listIterator();
	}

	public void setForwardingContextSupplier(final ContextSupplier forwardContextSupplier){
		this.forwardContextSupplier = forwardContextSupplier;
	}

	public ContextSupplier getOpenContextSupplier(){
		return this.openContextSupplier;
	}

    @Override
    public void messageReceived(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) {
		ctx.flush();
	}

	private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
		if (!req.decoderResult().isSuccess()) {
			SockHandler.sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
			return;
		}

		if (req.method() != GET) {
			SockHandler.sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
			return;
		}

		if ("/".equals(req.uri())) {
			ByteBuf content = PageNotReal.getContent(getWebSocketLocation(req));
			FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
			res.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8");
			HttpHeaderUtil.setContentLength(res, content.readableBytes());
			SockHandler.sendHttpResponse(ctx, req, res);
			return;
		}
		if ("/favicon.ico".equals(req.uri())) {
			FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
			SockHandler.sendHttpResponse(ctx, req, res);
			return;
		}

		WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
				getWebSocketLocation(req), null, false);
		this.handshaker = wsFactory.newHandshaker(req);
		if (this.handshaker == null) {
			WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
		} else {
			this.handshaker.handshake(ctx.channel(), req);
			this.openContexts.add(ctx);
		}
	}

	private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {

		if (frame instanceof CloseWebSocketFrame) {
			handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
			return;
		}
		if (frame instanceof PingWebSocketFrame) {
			ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
			return;
		}
		if (!(frame instanceof TextWebSocketFrame)) {
			throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
					.getName()));
		}

		String request = ((TextWebSocketFrame) frame).text();
		//System.err.printf("%s received %s%n", ctx.channel(), request);
		for (Iterator<ChannelHandlerContext> fwdIter = this.forwardContextSupplier.get(); fwdIter.hasNext();){
			fwdIter.next().channel().write(new TextWebSocketFrame(request));
		}
	}

	private static void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
		if (res.status().code() != 200) {
			ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
			res.content().writeBytes(buf);
			buf.release();
			HttpHeaderUtil.setContentLength(res, res.content().readableBytes());
		}

		ChannelFuture f = ctx.channel().writeAndFlush(res);
		if (!HttpHeaderUtil.isKeepAlive(req) || res.status().code() != 200) {
			f.addListener(ChannelFutureListener.CLOSE);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();
		ctx.close();
	}

	private String getWebSocketLocation(FullHttpRequest req) {
		String location =  req.headers().get(HOST) + path;
		if (this.ssl) {
			return "wss://" + location;
		} else {
			return "ws://" + location;
		}
	}
}
