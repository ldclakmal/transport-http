/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.transport.http.netty.contentaware.listeners;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.config.SenderConfiguration;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpConnectorListener;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.util.TestUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A class which read and write content through streams
 */
public class RequestResponseCreationStreamingListener implements HttpConnectorListener {

    private static final Logger LOG = LoggerFactory.getLogger(RequestResponseCreationStreamingListener.class);

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onMessage(HttpCarbonMessage httpRequest) {
        executor.execute(() -> {
            try {
                HttpMessageDataStreamer streamer = new HttpMessageDataStreamer(httpRequest);
                InputStream inputStream = streamer.getInputStream();
                byte[] bytes = IOUtils.toByteArray(inputStream);

                HttpCarbonMessage newMsg = httpRequest.cloneCarbonMessageWithOutData();
                OutputStream outputStream = new HttpMessageDataStreamer(newMsg).getOutputStream();
                outputStream.write(bytes);
                outputStream.flush();
                outputStream.close();
                newMsg.setProperty(Constants.HTTP_HOST, TestUtil.TEST_HOST);
                newMsg.setProperty(Constants.HTTP_PORT, TestUtil.HTTP_SERVER_PORT);

                HttpWsConnectorFactory httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();
                HttpClientConnector clientConnector =
                        httpWsConnectorFactory.createHttpClientConnector(new HashMap<>(), new SenderConfiguration());
                HttpResponseFuture future = clientConnector.send(newMsg);
                future.setHttpConnectorListener(new HttpConnectorListener() {
                    @Override
                    public void onMessage(HttpCarbonMessage httpMessage) {
                        executor.execute(() -> {
                            HttpCarbonMessage newMsg = httpMessage.cloneCarbonMessageWithOutData();
                            OutputStream outputStream = new HttpMessageDataStreamer(newMsg).getOutputStream();
                            try {
                                HttpMessageDataStreamer streamer = new HttpMessageDataStreamer(httpMessage);
                                InputStream inputStream = streamer.getInputStream();
                                byte[] bytes = IOUtils.toByteArray(inputStream);
                                outputStream.write(bytes);
                                outputStream.flush();
                                outputStream.close();
                            } catch (IOException e) {
                                throw new RuntimeException("Cannot read Input Stream from Response", e);
                            }
                            try {
                                httpRequest.respond(newMsg);
                            } catch (ServerConnectorException e) {
                                LOG.error("Error occurred during message notification: " + e.getMessage());
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable throwable) {

                    }
                });

            } catch (Exception e) {
                LOG.error("Error while reading stream", e);
            }
        });
    }

    @Override
    public void onError(Throwable throwable) {

    }
}
