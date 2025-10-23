package dev.waterdog.waterdogpe.network.netease.codec;

import dev.waterdog.waterdogpe.network.connection.codec.compression.CompressionType;
import dev.waterdog.waterdogpe.network.connection.codec.compression.ProxiedCompressionCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.data.CompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;

import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Netease版本的压缩编解码器
 */
@Log4j2
public class NetEaseCompressionCodec extends ProxiedCompressionCodec {
    
    private static final int MAX_DECOMPRESSION_SIZE = 3 * 1024 * 1024; // 3MB
    
    public NetEaseCompressionCodec(CompressionStrategy strategy, boolean prefixed) {
        super(strategy, prefixed);
        log.debug("Netease压缩编解码器已创建，前缀启用: {}", prefixed);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) throws Exception {
        ByteBuf uncompressed = msg.getUncompressed();
        if (uncompressed == null) {
            throw new IllegalStateException("Batch was not encoded before");
        }

        if (uncompressed.readableBytes() == 0) {
            log.warn("[{}] 输入数据为空，跳过编码", ctx.channel().remoteAddress());
            return;
        }

        try {
            // NetworkSettings包 - 无压缩
            byte secondByte = uncompressed.getByte(1);
            int secondValue = secondByte & 0xFF;
            if (secondValue == 0x8F) {
                ByteBuf finalData = ctx.alloc().directBuffer(uncompressed.readableBytes() + 1);
                finalData.writeByte(0xFE);
                finalData.writeBytes(uncompressed, uncompressed.readerIndex(), uncompressed.readableBytes());
                
                out.add(finalData.retain());
                finalData.release();
            } else {
                // ZLIB_RAW压缩
                try {
                    ByteBuf compressedData = compressWithZlibRaw(ctx, uncompressed);
                    ByteBuf finalData = ctx.alloc().directBuffer(compressedData.readableBytes() + 2);
                    finalData.writeByte(0xFE);
                    finalData.writeByte(0x00);
                    finalData.writeBytes(compressedData);
                    
                    out.add(finalData.retain());
                    finalData.release();
                    compressedData.release();
                } catch (Exception e) {
                    ByteBuf finalData = ctx.alloc().directBuffer(uncompressed.readableBytes() + 1);
                    finalData.writeByte(0xFE);
                    finalData.writeBytes(uncompressed, uncompressed.readerIndex(), uncompressed.readableBytes());
                    
                    out.add(finalData.retain());
                    finalData.release();
                }
            }
        } catch (Exception e) {
            log.error("[{}] NetEase编码失败: {}", ctx.channel().remoteAddress(), e.getMessage(), e);
            throw e;
        }
    }
    
    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) throws Exception {
        ByteBuf compressed = msg.getCompressed();
        if (compressed == null || !compressed.isReadable()) {
            log.debug("[{}] 跳过空的压缩数据包", ctx.channel().remoteAddress());
            return;
        }

        if (compressed.readableBytes() >= 1) {
            byte firstByte = compressed.getByte(0);
            if ((firstByte & 0xFF) == 0xFF) {
                ByteBuf batchPayload = compressed.slice(1, compressed.readableBytes() - 1);
                compressed = batchPayload;
            }
        }

        try {
            if (compressed.readableBytes() > MAX_DECOMPRESSION_SIZE) {
                throw new IllegalStateException("Compressed data too large: " + compressed.readableBytes());
            }
            
            ByteBuf dataToProcess;
            CompressionAlgorithm algorithm;
            ByteBuf decompressed = null;
            
            if (compressed.readableBytes() == 0) {
                decompressed = ctx.alloc().directBuffer(0);
                algorithm = PacketCompressionAlgorithm.NONE;
            } else {
                byte header = compressed.getByte(0);
                int headerValue = header & 0xFF;
                if (headerValue == 0x00) {
                    dataToProcess = compressed.slice(1, compressed.readableBytes() - 1);
                } else {
                    dataToProcess = compressed.slice();
                }
                
                // 逐个尝试
                try {
                    decompressed = decompressWithZlibRaw(ctx, dataToProcess);
                    algorithm = PacketCompressionAlgorithm.ZLIB;
                } catch (Exception e) {
                    try {
                        decompressed = decompressWithZlib(ctx, dataToProcess);
                        algorithm = PacketCompressionAlgorithm.ZLIB;
                    } catch (Exception ex) {
                        decompressed = decompressWithNone(ctx, compressed);
                        algorithm = PacketCompressionAlgorithm.NONE;
                    }
                }
            }
            
            msg.setAlgorithm(algorithm);
            msg.setUncompressed(decompressed);
            
            this.onDecompressed(ctx, msg);
            out.add(msg.retain());
        } catch (Exception e) {
            log.error("[{}] NetEase解码失败，数据大小: {} bytes", ctx.channel().remoteAddress(), compressed.readableBytes(), e);
            throw e;
        }
    }

    private ByteBuf decompressWithNone(ChannelHandlerContext ctx, ByteBuf compressedData) {
        ByteBuf result = ctx.alloc().directBuffer(compressedData.readableBytes());
        compressedData.markReaderIndex();
        result.writeBytes(compressedData);
        compressedData.resetReaderIndex();
        return result;
    }
    
    private ByteBuf decompressWithZlib(ChannelHandlerContext ctx, ByteBuf compressedData) throws DataFormatException {
        return _decompressWithZlib(ctx, compressedData, false);
    }

    private ByteBuf decompressWithZlibRaw(ChannelHandlerContext ctx, ByteBuf compressedData) throws DataFormatException {
        return _decompressWithZlib(ctx, compressedData, true);
    }

    private ByteBuf _decompressWithZlib(ChannelHandlerContext ctx, ByteBuf compressedData, boolean isRaw) throws DataFormatException {
        Inflater inflater;
        if (isRaw == true) {
            inflater = new Inflater(true);
        } else {
            inflater = new Inflater();
        }
        try {
            byte[] compressedBytes = new byte[compressedData.readableBytes()];
            compressedData.markReaderIndex();
            compressedData.readBytes(compressedBytes);
            compressedData.resetReaderIndex();
            inflater.setInput(compressedBytes);
            
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput()) {
                        throw new DataFormatException("ZLIB: 数据不完整");
                    }
                    break;
                }
                outputStream.write(buffer, 0, count);
                
                // 防止内存溢出
                if (outputStream.size() > MAX_DECOMPRESSION_SIZE) {
                    throw new DataFormatException("ZLIB: 解压后数据过大");
                }
            }
            
            byte[] decompressedBytes = outputStream.toByteArray();
            ByteBuf result = ctx.alloc().directBuffer(decompressedBytes.length);
            result.writeBytes(decompressedBytes);
            return result;
        } finally {
            inflater.end();
        }
    }

    private ByteBuf compressWithZlibRaw(ChannelHandlerContext ctx, ByteBuf data) throws Exception {
        if (data.readableBytes() == 0) {
            log.debug("[{}] 输入数据为空，返回空的压缩结果", ctx.channel().remoteAddress());
            return ctx.alloc().directBuffer(0);
        }
        
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            byte[] input = new byte[data.readableBytes()];
            data.markReaderIndex();
            data.readBytes(input);
            data.resetReaderIndex();
            deflater.setInput(input);
            deflater.finish();
            
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count > 0) {
                    outputStream.write(buffer, 0, count);
                }
            }
            
            byte[] compressedBytes = outputStream.toByteArray();
            if (compressedBytes.length == 0 && input.length > 0) {
                ByteBuf result = ctx.alloc().directBuffer(input.length);
                result.writeBytes(input);
                return result;
            }
            
            ByteBuf result = ctx.alloc().directBuffer(compressedBytes.length);
            result.writeBytes(compressedBytes);
            return result;
        } finally {
            deflater.end();
        }
    }

    @Override
    protected byte getCompressionHeader0(CompressionAlgorithm algorithm) {
        if (algorithm instanceof CompressionType type) {
            if (type.getHeaderId() >= 0) {
                return type.getHeaderId();
            }
        }
        return super.getCompressionHeader0(algorithm);
    }

    @Override
    protected CompressionAlgorithm getCompressionAlgorithm0(byte header) {
        CompressionType type = CompressionType.fromHeaderId(header);
        if (type != null) {
            return type;
        }
        return super.getCompressionAlgorithm0(header);
    }
}