/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.protocol.dubbo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.io.Bytes;
import com.alibaba.dubbo.common.io.UnsafeByteArrayInputStream;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.codec.ExchangeCodec;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import static com.alibaba.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.encodeInvocationArgument;

/**
 * Dubbo codec.
 *
 * @author qianlei
 * @author chao.liuc
 */
public class DubboCodec extends ExchangeCodec implements Codec2 {

    private static final Logger log = LoggerFactory.getLogger(DubboCodec.class);

    public static final String NAME = "dubbo";

    public static final String DUBBO_VERSION = Version.getVersion(DubboCodec.class, Version.getVersion());

    public static final byte RESPONSE_WITH_EXCEPTION = 0;

    public static final byte RESPONSE_VALUE = 1;

    public static final byte RESPONSE_NULL_VALUE = 2;

    public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    public static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];

    @Override
    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData)
            throws IOException {
        // For now, just ignore DECODE_IN_IO_THREAD_KEY
        Invocation invocation = (Invocation) requestData;
        RpcResult rpcResult = new RpcResult();

        byte flag = in.readByte();
        switch (flag) {
        case RESPONSE_NULL_VALUE:
            break;
        case RESPONSE_VALUE:
            try {
                Type[] returnType = RpcUtils.getReturnTypes(invocation);
                rpcResult.setValue(returnType == null || returnType.length == 0 ? in.readObject() :
                        (returnType.length == 1 ? in.readObject((Class<?>) returnType[0])
                                : in.readObject((Class<?>) returnType[0], returnType[1])));
            } catch (ClassNotFoundException e) {
                throw new IOException(StringUtils.toString("Read response data failed.", e));
            }
            break;
        case DubboCodec.RESPONSE_WITH_EXCEPTION:
            try {
                Object obj = in.readObject();
                if (!(obj instanceof Throwable))
                    throw new IOException("Response data error, expect Throwable, but get " + obj);
                rpcResult.setException((Throwable) obj);
            } catch (ClassNotFoundException e) {
                throw new IOException(StringUtils.toString("Read response data failed.", e));
            }
            break;
        default:
            throw new IOException("Unknown result flag, expect '0' '1' '2', get " + flag);
        }
        return rpcResult;
    }

    @Override protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        RpcInvocation rpcInvocation = new RpcInvocation();
        rpcInvocation.setAttachment(Constants.DUBBO_VERSION_KEY, in.readUTF());
        rpcInvocation.setAttachment(Constants.PATH_KEY, in.readUTF());
        rpcInvocation.setAttachment(Constants.VERSION_KEY, in.readUTF());

        rpcInvocation.setMethodName(in.readUTF());
        try {
            Object[] args;
            Class<?>[] pts;
            String desc = in.readUTF();
            if (desc.length() == 0) {
                pts = DubboCodec.EMPTY_CLASS_ARRAY;
                args = DubboCodec.EMPTY_OBJECT_ARRAY;
            } else {
                pts = ReflectUtils.desc2classArray(desc);
                args = new Object[pts.length];
                for (int i = 0; i < args.length; i++) {
                    try {
                        args[i] = in.readObject(pts[i]);
                    } catch (Exception e) {
                        if (log.isWarnEnabled()) {
                            log.warn("Decode argument failed: " + e.getMessage(), e);
                        }
                    }
                }
            }
            rpcInvocation.setParameterTypes(pts);

            Map<String, String> map = (Map<String, String>) in.readObject(Map.class);
            if (map != null && map.size() > 0) {
                Map<String, String> attachment = rpcInvocation.getAttachments();
                if (attachment == null) {
                    attachment = new HashMap<String, String>();
                }
                attachment.putAll(map);
                rpcInvocation.setAttachments(attachment);
            }
            //decode argument ,may be callback
            for (int i = 0; i < args.length; i++) {
                args[i] = CallbackServiceCodec.decodeInvocationArgument(channel, rpcInvocation, pts, i, args[i]);
            }

            rpcInvocation.setArguments(args);

        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read invocation data failed.", e));
        }

        return rpcInvocation;
    }

    private byte[] readMessageData(InputStream is) throws IOException {
        if (is.available() > 0) {
            byte[] result = new byte[is.available()];
            is.read(result);
            return result;
        }
        return new byte[]{};
    }

    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        RpcInvocation inv = (RpcInvocation) data;

        out.writeUTF(inv.getAttachment(Constants.DUBBO_VERSION_KEY, DUBBO_VERSION));
        out.writeUTF(inv.getAttachment(Constants.PATH_KEY));
        out.writeUTF(inv.getAttachment(Constants.VERSION_KEY));

        out.writeUTF(inv.getMethodName());
        out.writeUTF(ReflectUtils.getDesc(inv.getParameterTypes()));
        Object[] args = inv.getArguments();
        if (args != null)
        for (int i = 0; i < args.length; i++){
            out.writeObject(encodeInvocationArgument(channel, inv, i));
        }
        out.writeObject(inv.getAttachments());
    }

    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        Result result = (Result) data;

        Throwable th = result.getException();
        if (th == null) {
            Object ret = result.getValue();
            if (ret == null) {
                out.writeByte(RESPONSE_NULL_VALUE);
            } else {
                out.writeByte(RESPONSE_VALUE);
                out.writeObject(ret);
            }
        } else {
            out.writeByte(RESPONSE_WITH_EXCEPTION);
            out.writeObject(th);
        }
    }
}
