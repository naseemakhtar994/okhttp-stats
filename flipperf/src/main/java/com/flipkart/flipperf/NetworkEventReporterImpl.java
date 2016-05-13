package com.flipkart.flipperf;

import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.SparseArray;

import com.flipkart.flipperf.model.RequestResponseModel;
import com.flipkart.flipperf.network.NetworkChangeReceiver;
import com.flipkart.flipperf.network.NetworkHelper;
import com.flipkart.flipperf.network.OnNetworkChangeListener;
import com.flipkart.flipperf.response.CountingInputStream;
import com.flipkart.flipperf.response.ResponseHandler;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.ResponseBody;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by anirudh.r on 09/05/16 at 12:32 PM.
 * <p>
 * Whenever we get a {@link NetworkEventReporterImpl#requestToBeSent(InspectorRequest)} callback, we create a {@link CurrentExecutingRequest}
 * object and add it to the {@link SparseArray}.
 * <p>
 * {@link CurrentExecutingRequest} indicates those request that are currently in execution.
 * <p>
 * There are two cases by which we will get a response callback:
 * <p>
 * 1st Case : When response do not have Content-Length
 * Whenever we receive {@link NetworkEventReporterImpl#responseReceived(InspectorResponse)} callback, we get the corresponding {@link CurrentExecutingRequest}
 * from the {@link SparseArray} using the requestId, and create a {@link RequestResponseModel} and send it accros
 * <p>
 * 2nd Case : When response have Content-Length
 * Whenever we receive {@link NetworkEventReporterImpl#responseDataReceived(InspectorResponse, int)} callback, the same steps are repeated as in
 * {@link NetworkEventReporterImpl#responseReceived(InspectorResponse)}
 * <p>
 * After the Response has been received, the particular {@link CurrentExecutingRequest} is removed from the {@link SparseArray}.
 * <p>
 * In case of any {@link IOException} during the {@link com.squareup.okhttp.Interceptor.Chain#proceed(Request)},
 * {@link NetworkEventReporter#httpExchangeError(InspectorRequest, IOException)} gets called with appropriate error message.
 * <p>
 * In case of any {@link IOException} during the {@link ResponseBody#byteStream()}, {@link NetworkEventReporter#responseInputStreamError(InspectorResponse, IOException)}
 * gets called with appropriate error message.
 * <p>
 *             with content length
 *           -------------------------->  {@link NetworkEventReporterImpl#responseReceived(InspectorResponse)}
 *          |
 *          |
 *          |
 * REQUEST --->
 *          |
 *          |
 *          |  without content length
 *            -------------------------->  {@link NetworkEventReporterImpl#responseDataReceived(InspectorResponse, int)}
 */
public class NetworkEventReporterImpl implements NetworkEventReporter, OnNetworkChangeListener {

    private Handler mHandler;
    private boolean mIsReporterEnabled = false;
    private String mNetworkType;
    private SparseArray<CurrentExecutingRequest> mCurrentRequestArray;
    private NetworkManager mNetworkManager;

    @VisibleForTesting
    public SparseArray<CurrentExecutingRequest> getCurrentRequestArray() {
        return mCurrentRequestArray;
    }

    @Override
    public void onInitialized(Context context, Handler handler, NetworkManager networkManager) {
        this.mHandler = handler;
        this.mCurrentRequestArray = new SparseArray<>();
        this.mNetworkType = NetworkHelper.getNetworkType(context);
        this.mNetworkManager = networkManager;
        this.mNetworkManager.setNetworkType(mNetworkType);
    }

    @Override
    public void setEnabled(boolean isEnabled) {
        this.mIsReporterEnabled = isEnabled;
    }

    @Override
    public boolean isReporterEnabled() {
        return mIsReporterEnabled;
    }

    @Override
    public void requestToBeSent(InspectorRequest inspectorRequest) {
        final CurrentExecutingRequest currentRequest = new CurrentExecutingRequest(inspectorRequest);
        this.mHandler.post(new Runnable() {
            @Override
            public void run() {
                mCurrentRequestArray.put(currentRequest.getCurrentRequestId(), currentRequest);
            }
        });
    }

    @Override
    public void responseReceived(final InspectorResponse inspectorResponse) {
        final int requestId = inspectorResponse.requestId();
        this.mHandler.post(new Runnable() {
            @Override
            public void run() {
                //check if request associated exists in the sparsearray
                if (mCurrentRequestArray.indexOfKey(requestId) >= 0 && inspectorResponse.hasContentLength()) {
                    CurrentExecutingRequest currentRequest = mCurrentRequestArray.get(requestId);
                    if (currentRequest.getCurrentRequestId() == requestId) {
                        RequestResponseModel requestResponseModel = new RequestResponseModel();
                        requestResponseModel.setRequestId(requestId);
                        requestResponseModel.setRequestSize(currentRequest.getCurrentRequestSize());
                        requestResponseModel.setRequestUrl(currentRequest.getCurrentRequestUrl());
                        requestResponseModel.setRequestMethodType(currentRequest.getCurrentRequestMethod());
                        requestResponseModel.setHostName(currentRequest.getCurrentHostName());
                        requestResponseModel.setResponseSize(inspectorResponse.responseSize());
                        requestResponseModel.setResponseStatusCode(inspectorResponse.statusCode());
                        requestResponseModel.setResponseTime(inspectorResponse.responseTime());
                        requestResponseModel.setApiSpeed(Double.parseDouble(requestResponseModel.getResponseSize()) / requestResponseModel.getResponseTime());
                        requestResponseModel.setNetworkType(mNetworkType);

                        mNetworkManager.onResponseReceived(requestResponseModel);
                        mCurrentRequestArray.remove(requestId);
                    }
                }
            }
        });
    }

    @Override
    public void httpExchangeError(final InspectorRequest inspectorRequest, final IOException e) {
        final int requestId = inspectorRequest.requestId();
        this.mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCurrentRequestArray.indexOfKey(requestId) >= 0) {
                    CurrentExecutingRequest currentRequest = mCurrentRequestArray.get(requestId);
                    if (currentRequest.getCurrentRequestId() == requestId) {
                        RequestResponseModel requestResponseModel = new RequestResponseModel();
                        requestResponseModel.setRequestId(currentRequest.getCurrentRequestId());
                        requestResponseModel.setRequestUrl(currentRequest.getCurrentRequestUrl());
                        requestResponseModel.setRequestMethodType(currentRequest.getCurrentRequestMethod());
                        requestResponseModel.setHostName(currentRequest.getCurrentHostName());
                        requestResponseModel.setHttpExchangeErrorMessage(e.getMessage());
                        requestResponseModel.setNetworkType(mNetworkType);

                        mNetworkManager.onHttpExchangeError(requestResponseModel);
                        mCurrentRequestArray.remove(requestId);
                    }
                }
            }
        });
    }

    @Override
    public InputStream interpretResponseStream(@Nullable InputStream inputStream, ResponseHandler responseHandler) {
        return new CountingInputStream(inputStream, responseHandler);
    }

    @Override
    public void responseDataReceived(final InspectorResponse inspectorResponse, final int dataLength) {
        final int requestId = inspectorResponse.requestId();
        this.mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCurrentRequestArray.indexOfKey(requestId) >= 0) {
                    CurrentExecutingRequest currentRequest = mCurrentRequestArray.get(requestId);
                    if (currentRequest.getCurrentRequestId() == requestId) {
                        RequestResponseModel requestResponseModel = new RequestResponseModel();
                        requestResponseModel.setRequestId(requestId);
                        requestResponseModel.setRequestSize(currentRequest.getCurrentRequestSize());
                        requestResponseModel.setRequestUrl(currentRequest.getCurrentRequestUrl());
                        requestResponseModel.setRequestMethodType(currentRequest.getCurrentRequestMethod());
                        requestResponseModel.setHostName(currentRequest.getCurrentHostName());
                        requestResponseModel.setResponseSize(String.valueOf(dataLength));
                        requestResponseModel.setResponseStatusCode(inspectorResponse.statusCode());
                        requestResponseModel.setResponseTime(inspectorResponse.responseTime());
                        requestResponseModel.setApiSpeed(Double.parseDouble(requestResponseModel.getResponseSize()) / requestResponseModel.getResponseTime());
                        requestResponseModel.setNetworkType(mNetworkType);

                        mNetworkManager.onResponseReceived(requestResponseModel);
                        mCurrentRequestArray.remove(requestId);
                    }
                }
            }
        });
    }

    @Override
    public void responseInputStreamError(InspectorResponse inspectorResponse, final IOException e) {
        final int requestId = inspectorResponse.requestId();
        this.mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCurrentRequestArray.indexOfKey(requestId) >= 0) {
                    CurrentExecutingRequest currentRequest = mCurrentRequestArray.get(requestId);
                    if (currentRequest.getCurrentRequestId() == requestId) {
                        RequestResponseModel requestResponseModel = new RequestResponseModel();
                        requestResponseModel.setRequestId(requestId);
                        requestResponseModel.setResponseInputStreamError(e.getMessage());
                        requestResponseModel.setNetworkType(mNetworkType);

                        mNetworkManager.onResponseInputStreamError(requestResponseModel);
                        mCurrentRequestArray.remove(requestId);
                    }
                }
            }
        });
    }

    @Override
    public void onNetworkChange(String networkType) {
        mNetworkType = networkType;
        this.mNetworkManager.setNetworkType(mNetworkType);
    }

    private void registerReceiver(Context context) {
        NetworkChangeReceiver networkChangeReceiver = new NetworkChangeReceiver(this);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(networkChangeReceiver, intentFilter);
    }

    public class CurrentExecutingRequest {
        private final int mCurrentRequestId;
        private final String mCurrentRequestUrl;
        private final String mCurrentRequestMethod;
        private final String mCurrentRequestSize;
        private final String mCurrentHostName;

        public CurrentExecutingRequest(InspectorRequest inspectorRequest) {
            this.mCurrentRequestId = inspectorRequest.requestId();
            this.mCurrentRequestUrl = inspectorRequest.url();
            this.mCurrentRequestMethod = inspectorRequest.method();
            this.mCurrentRequestSize = inspectorRequest.requestSize();
            this.mCurrentHostName = inspectorRequest.hostName();
        }

        public String getCurrentHostName() {
            return mCurrentHostName;
        }

        public int getCurrentRequestId() {
            return mCurrentRequestId;
        }

        public String getCurrentRequestMethod() {
            return mCurrentRequestMethod;
        }

        public String getCurrentRequestUrl() {
            return mCurrentRequestUrl;
        }

        public String getCurrentRequestSize() {
            return mCurrentRequestSize;
        }
    }
}
