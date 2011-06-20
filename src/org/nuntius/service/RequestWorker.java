package org.nuntius.service;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.nuntius.authenticator.Authenticator;
import org.nuntius.client.*;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

public class RequestWorker extends Thread {
    private static final String TAG = RequestWorker.class.getSimpleName();
    private static final int MSG_REQUEST_JOB = 1;

    private static final long DEFAULT_RETRY_DELAY = 10000;

    private PauseHandler mHandler;
    private final Context mContext;
    private final EndpointServer mServer;
    private String mAuthToken;

    private RequestClient mClient;
    private RequestListener mListener;

    /** Pending jobs queue - will be used on thread start to initialize the messages. */
    static public LinkedList<RequestJob> pendingJobs = new LinkedList<RequestJob>();

    public RequestWorker(Context context, EndpointServer server) {
        mContext = context;
        mServer = server;
    }

    public void setResponseListener(RequestListener listener) {
        this.mListener = listener;
    }

    public void run() {
        Looper.prepare();
        mAuthToken = Authenticator.getDefaultAccountToken(mContext);
        Log.i(TAG, "using token: " + mAuthToken);

        mClient = new RequestClient(mServer, mAuthToken);

        // create handler and empty pending jobs queue
        // this must be done synchronized on the queue
        synchronized (pendingJobs) {
            mHandler = new PauseHandler(new LinkedList<RequestJob>(pendingJobs));
            pendingJobs = new LinkedList<RequestJob>();
        }

        Looper.loop();
    }

    private final class PauseHandler extends Handler {
        private boolean mRunning;
        private Queue<RequestJob> mQueue;

        public PauseHandler(Queue<RequestJob> pending) {
            mQueue = new LinkedList<RequestJob>(pending);
            resume();
        }

        public synchronized void stop() {
            mRunning = false;
            mClient.abort();
            getLooper().quit();
        }

        public synchronized void pause() {
            mRunning = false;
        }

        public synchronized void resume() {
            mRunning = true;

            // requeue the old messages
            Log.i(TAG, "processing pending jobs queue (" + mQueue.size() + " jobs)");
            for (RequestJob job = mQueue.poll(); job != null; job = mQueue.poll()) {
                Log.i(TAG, "requeueing pending job " + job);
                sendMessage(obtainMessage(MSG_REQUEST_JOB, job));
            }
        }

        @Override
        public synchronized void handleMessage(Message msg) {
            if (msg.what == MSG_REQUEST_JOB) {
                // not running - queue message
                if (!mRunning) {
                    Log.w(TAG, "request worker is not running - queueing message");
                    mQueue.add((RequestJob) msg.obj);
                    return;
                }

                RequestJob job = (RequestJob) msg.obj;
                Log.w(TAG, job.toString());

                // try to use the custom listener
                RequestListener listener = job.getListener();
                if (listener == null)
                    listener = mListener;

                List<StatusResponse> list;
                try {
                    // FIXME this is temporary
                    if (job instanceof MessageSender) {
                        MessageSender mess = (MessageSender) job;
                        list = mClient.message(new String[] { mess.getUserId() }, mess.getMime(), mess.getContent().getBytes(), listener);
                    }
                    else {
                        list = mClient.request(job.getCommand(), job.getParams(), job.getContent());
                    }

                    if (listener != null)
                        listener.response(job, list);
                } catch (IOException e) {
                    boolean requeue = true;
                    Log.e(TAG, "request error", e);
                    if (listener != null)
                        requeue = listener.error(job, e);

                    if (requeue) {
                        Log.i(TAG, "requeuing job " + job);
                        push(job, DEFAULT_RETRY_DELAY);
                    }
                }
            }

            else
                super.handleMessage(msg);
        };
    }

    public void push(RequestJob job) {
        push(job, 0);
    }

    public void push(RequestJob job, long delay) {
        // max wait time 10 seconds
        int retries = 20;

        while(!isAlive() || retries <= 0) {
            try {
                // 500ms should do the job...
                Thread.sleep(500);
                Thread.yield();
                retries--;
            } catch (InterruptedException e) {
                // interrupted - do not send message
                return;
            }
        }

        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_REQUEST_JOB, job),
                delay);
    }

    /** Pauses the request queue. */
    public void pause() {
        if (mHandler != null)
            mHandler.pause();
    }

    /** Resumes the request queue. */
    public void resume2() {
        if (mHandler != null)
            mHandler.resume();
    }

    /** Shuts down this request worker gracefully. */
    public void shutdown() {

        Log.w(TAG, "shutting down");
        if (mHandler != null)
            mHandler.stop();

        Log.w(TAG, "exiting");
        mHandler = null;
    }
}
