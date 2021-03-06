package org.infinispan.server.hotrod;

import static org.infinispan.server.hotrod.ResponseWriting.writeResponse;

import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.BitSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.security.auth.Subject;

import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.commons.marshall.jboss.GenericJBossMarshaller;
import org.infinispan.security.Security;
import org.infinispan.server.core.transport.NettyTransport;
import org.infinispan.server.hotrod.iteration.IterableIterationResult;
import org.infinispan.server.hotrod.logging.Log;
import org.infinispan.server.hotrod.util.BulkUtil;
import org.infinispan.tasks.TaskContext;
import org.infinispan.tasks.TaskManager;
import org.infinispan.util.KeyValuePair;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Handler that performs actual cache operations.  Note this handler should be on a separate executor group than the
 * decoder.
 *
 * @author wburns
 * @since 9.0
 */
public class ContextHandler extends SimpleChannelInboundHandler<CacheDecodeContext> {
   private final static Log log = LogFactory.getLog(ContextHandler.class, Log.class);

   private final HotRodServer server;
   private final NettyTransport transport;
   private final Executor executor;

   public ContextHandler(HotRodServer server, NettyTransport transport, Executor executor) {
      this.server = server;
      this.transport = transport;
      this.executor = executor;
   }

   @Override
   protected void channelRead0(ChannelHandlerContext ctx, CacheDecodeContext msg) throws Exception {
      executor.execute(() -> {
         try {
            Subject subject = msg.subject;
            if (subject == null)
               realRead(ctx, msg);
            else Security.doAs(subject, (PrivilegedExceptionAction<Void>) () -> {
               realRead(ctx, msg);
               return null;
            });
         } catch (PrivilegedActionException e) {
            ctx.fireExceptionCaught(e.getCause());
         } catch (Exception e) {
            ctx.fireExceptionCaught(e);
         }
      });
   }

   protected void realRead(ChannelHandlerContext ctx, CacheDecodeContext msg) throws Exception {
      HotRodHeader h = msg.header;
      switch (h.op) {
         case PutRequest:
            writeResponse(msg, ctx.channel(), msg.put());
            break;
         case PutIfAbsentRequest:
            writeResponse(msg, ctx.channel(), msg.putIfAbsent());
            break;
         case ReplaceRequest:
            writeResponse(msg, ctx.channel(), msg.replace());
            break;
         case ReplaceIfUnmodifiedRequest:
            writeResponse(msg, ctx.channel(), msg.replaceIfUnmodified());
            break;
         case ContainsKeyRequest:
            writeResponse(msg, ctx.channel(), msg.containsKey());
            break;
         case GetRequest:
         case GetWithVersionRequest:
            writeResponse(msg, ctx.channel(), msg.get());
            break;
         case GetWithMetadataRequest:
            writeResponse(msg, ctx.channel(), msg.getKeyMetadata());
            break;
         case RemoveRequest:
            writeResponse(msg, ctx.channel(), msg.remove());
            break;
         case RemoveIfUnmodifiedRequest:
            writeResponse(msg, ctx.channel(), msg.removeIfUnmodified());
            break;
         case PingRequest:
            writeResponse(msg, ctx.channel(), new Response(h.version, h.messageId, h.cacheName,
                  h.clientIntel, OperationResponse.PingResponse, OperationStatus.Success, h.topologyId));
            break;
         case StatsRequest:
            writeResponse(msg, ctx.channel(), msg.decoder.createStatsResponse(msg, transport));
            break;
         case ClearRequest:
            writeResponse(msg, ctx.channel(), msg.clear());
            break;
         case SizeRequest:
            writeResponse(msg, ctx.channel(), new SizeResponse(h.version, h.messageId, h.cacheName,
                  h.clientIntel, h.topologyId, msg.cache.size()));
            break;
         case ExecRequest:
            ExecRequestContext execContext = (ExecRequestContext) msg.operationDecodeContext;
            TaskManager taskManager = SecurityActions.getCacheGlobalComponentRegistry(msg.cache).getComponent(TaskManager.class);
            Marshaller marshaller;
            if (server.getMarshaller() != null) {
               marshaller = server.getMarshaller();
            } else {
               marshaller = new GenericJBossMarshaller();
            }
            byte[] result = (byte[]) taskManager.runTask(execContext.getName(),
                  new TaskContext().marshaller(marshaller).cache(msg.cache).parameters(execContext.getParams())).get();
            writeResponse(msg, ctx.channel(),
                  new ExecResponse(h.version, h.messageId, h.cacheName, h.clientIntel, h.topologyId,
                        result == null ? new byte[]{} : result));
            break;
         case BulkGetRequest:
            int size = (int) msg.operationDecodeContext;
            if (CacheDecodeContext.isTrace) {
               log.tracef("About to create bulk response count = %d", size);
            }
            writeResponse(msg, ctx.channel(), new BulkGetResponse(h.version, h.messageId, h.cacheName, h.clientIntel,
                  h.topologyId, size, msg.cache.entrySet()));
            break;
         case BulkGetKeysRequest:
            int scope = (int) msg.operationDecodeContext;
            if (CacheDecodeContext.isTrace) {
               log.tracef("About to create bulk get keys response scope = %d", scope);
            }
            writeResponse(msg, ctx.channel(), new BulkGetKeysResponse(h.version, h.messageId, h.cacheName, h.clientIntel,
                  h.topologyId, scope, BulkUtil.getAllKeys(msg.cache, scope)));
            break;
         case QueryRequest:
            byte[] queryResult = server.query(msg.cache, (byte[]) msg.operationDecodeContext);
            writeResponse(msg, ctx.channel(),
                  new QueryResponse(h.version, h.messageId, h.cacheName, h.clientIntel, h.topologyId, queryResult));
            break;
         case AddClientListenerRequest:
            ClientListenerRequestContext clientContext = (ClientListenerRequestContext) msg.operationDecodeContext;
            server.getClientListenerRegistry().addClientListener(msg.decoder, ctx.channel(), h, clientContext.getListenerId(),
                  msg.cache, clientContext.isIncludeCurrentState(), new KeyValuePair<>(clientContext.getFilterFactoryInfo(),
                        clientContext.getConverterFactoryInfo()), clientContext.isUseRawData());
            break;
         case RemoveClientListenerRequest:
            byte[] listenerId = (byte[]) msg.operationDecodeContext;
            if (server.getClientListenerRegistry().removeClientListener(listenerId, msg.cache)) {
               writeResponse(msg, ctx.channel(), msg.decoder.createSuccessResponse(h, null));
            } else {
               writeResponse(msg, ctx.channel(), msg.decoder.createNotExecutedResponse(h, null));
            }
            break;
         case IterationStartRequest:
            IterationStartRequest iterationStart = (IterationStartRequest) msg.operationDecodeContext;

            Optional<BitSet> optionBitSet;
            if (iterationStart.getOptionBitSet().isPresent()) {
               optionBitSet = Optional.of(BitSet.valueOf(iterationStart.getOptionBitSet().get()));
            } else {
               optionBitSet = Optional.empty();
            }
            String iterationId = server.getIterationManager().start(msg.cache.getName(), optionBitSet,
                  iterationStart.getFactory(), iterationStart.getBatch(), iterationStart.isMetadata());
            writeResponse(msg, ctx.channel(), new IterationStartResponse(h.version, h.messageId, h.cacheName,
                  h.clientIntel, h.topologyId, iterationId));
            break;
         case IterationNextRequest:
            iterationId = (String) msg.operationDecodeContext;
            IterableIterationResult iterationResult = server.getIterationManager().next(msg.cache.getName(), iterationId);
            writeResponse(msg, ctx.channel(), new IterationNextResponse(h.version, h.messageId, h.cacheName,
                  h.clientIntel, h.topologyId, iterationResult));
            break;
         case IterationEndRequest:
            iterationId = (String) msg.operationDecodeContext;
            boolean removed = server.getIterationManager().close(msg.cache.getName(), iterationId);
            writeResponse(msg, ctx.channel(), new Response(h.version, h.messageId, h.cacheName, h.clientIntel,
                  OperationResponse.IterationEndResponse,
                  removed ? OperationStatus.Success : OperationStatus.InvalidIteration, h.topologyId));
            break;
         case PutAllRequest:
            msg.cache.putAll((Map<byte[], byte[]>) msg.operationDecodeContext, msg.buildMetadata());
            writeResponse(msg, ctx.channel(), msg.decoder.createSuccessResponse(h, null));
            break;
         case GetAllRequest:
            Map<byte[], byte[]> map = msg.cache.getAll((Set<byte[]>) msg.operationDecodeContext);
            writeResponse(msg, ctx.channel(), new GetAllResponse(h.version, h.messageId, h.cacheName,
                  h.clientIntel, h.topologyId, map));
            break;
         default:
            throw new IllegalArgumentException("Unsupported operation invoked: " + msg.header.op);
      }
   }

   @Override
   public void channelActive(ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
      log.tracef("Channel %s became active", ctx.channel());
      server.getClientListenerRegistry().findAndWriteEvents(ctx.channel());
   }

   @Override
   public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
      super.channelWritabilityChanged(ctx);
      log.tracef("Channel %s writability changed", ctx.channel());
      server.getClientListenerRegistry().findAndWriteEvents(ctx.channel());
   }

   @Override
   public boolean acceptInboundMessage(Object msg) throws Exception {
      // Faster than netty matcher
      return msg instanceof CacheDecodeContext;
   }
}
