package com.google.android.exoplayer2.demo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSink;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.DummyDataSource;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.FileDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.PriorityDataSource;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSink;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.CacheUtil;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.PriorityTaskManager;
import com.google.android.exoplayer2.util.Util;

import java.io.IOException;

/*
 *  Pre-caching Progressive stream with priority demo
 */
public class PreCacheProgressivePlayerActivity extends Activity implements Player.EventListener {
    // TODO Use your available uris
    private static final String URI_TEST1 = "http://192.168.75.251:8081/Videos/tianjin.ts";
    private static final String URI_TEST2 = "http://192.168.75.251:8081/Videos/wdmy.ts";
    protected String userAgent;
    FrameLayout root;
    PlayerView playerView;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private ExoPlaybackException playbackException;
    private DataSource.Factory dataSourceFactory;
    private DefaultRenderersFactory renderersFactory;
    private DefaultLoadControl loadControl;
    private PriorityTaskManager mPriorityTaskManager;

    // Pre-caching
    private String fixedCacheKey;
    private DataSpec dataSpec;
    private CacheUtil.CachingCounters mCounters;
    private Cache downloadCache;

    private static final int UPDATE_CACHE_COUNTER = 1;
    @SuppressLint("HandlerLeak")
    private Handler updateCounterHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what){
                case UPDATE_CACHE_COUNTER:
                    double downloadPercentage = (mCounters.totalCachedBytes() * 100d)
                            / mCounters.contentLength;
                    Log.d("progressive_download", "Cache counter = [" + downloadPercentage + "], " +
                            "cached bytes = [" + mCounters.totalCachedBytes() + "], " +
                            "total content bytes = [" + mCounters.contentLength + "]");
                    if (downloadPercentage >= 100.0f){
                        // startPlayer(BEAR_URI);
                        Log.e("progressive_download","Cache successfully!");
                        updateCounterHandler.removeCallbacksAndMessages(null);
                    }else {
                        updateCounterHandler.sendEmptyMessageDelayed(UPDATE_CACHE_COUNTER,1000);
                    }
                    break;
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.player_activity);
        playerView = findViewById(R.id.player_view);
        root = findViewById(R.id.root);
        playerView.requestFocus();
        int extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;
        renderersFactory =
                new DefaultRenderersFactory(this, extensionRendererMode);
        trackSelector = new DefaultTrackSelector();
        mCounters = new CacheUtil.CachingCounters();
        mPriorityTaskManager = new PriorityTaskManager();
       /*
        *  Start a task with high priority
        */
        startPlayer(URI_TEST1);
        /*
         * Pre-cache other task with low priority
         */
        Thread cacheThread = new Thread(new Cache1MTaskWithPriority(URI_TEST2));
        cacheThread.start();

        Log.d("progressive_download","Cache thread id = " +
                "[" + cacheThread.getName() + "]");

        updateCounterHandler.sendEmptyMessageDelayed(UPDATE_CACHE_COUNTER,1000);

    }


    private void startPlayer(String playUri) {
        /*
         * LoadControl with priority manager
         */
        loadControl = new DefaultLoadControl.Builder()
                .setPriorityTaskManager(mPriorityTaskManager)
                .createDefaultLoadControl();
        player = ExoPlayerFactory.newSimpleInstance(this,renderersFactory, trackSelector,loadControl);
        player.addListener(this);
        playerView.setPlayer(player);
        dataSourceFactory = buildDataSourceFactory();
        MediaSource mediaSource = buildMediaSource(Uri.parse(playUri),"");

        player.prepare(mediaSource);
        player.setPlayWhenReady(true);
    }

    /*
     * Test pre-cache with priority
     */
    class Cache1MTaskWithPriority implements Runnable{
        private String cacheUri;
        public Cache1MTaskWithPriority(String uri) {
            this.cacheUri = uri;
        }

        @Override
        public void run() {
            fixedCacheKey = CacheUtil.generateKey(Uri.parse(cacheUri));
            dataSpec = new DataSpec(Uri.parse(cacheUri),
                    0, 200 * 1024 * 1024, fixedCacheKey);

            /*
             * If a {@link PriorityTaskManager} is given, it's used to pause and resume caching depending
             * on {@code priority} and the priority of other tasks registered to the PriorityTaskManager.
             * Please note that it's the responsibility of the calling code to call{@link PriorityTaskManager#add}
             * to register with the manager before calling this method, and to call {@link PriorityTaskManager#remove}
             * afterwards to unregister.
             */
            mPriorityTaskManager.add(C.PRIORITY_DOWNLOAD);
            try {
                CacheUtil.cache(dataSpec,
                        getDownloadCache(),
                        buildPriorityCacheDataSource(false),
                        new byte[CacheUtil.DEFAULT_BUFFER_SIZE_BYTES],
                        mPriorityTaskManager,
                        C.PRIORITY_DOWNLOAD,
                        mCounters,
                        null,
                        false);
            } catch (IOException e) {
                Log.e("progressive_download","Pre-caching io exception!");
                e.printStackTrace();
            } catch (InterruptedException e) {
                Log.e("progressive_download","Pre-caching interrupt exception!");
                e.printStackTrace();
            }finally {
                mPriorityTaskManager.remove(C.PRIORITY_DOWNLOAD);
            }
            Log.e("progressive_download","Cache thread ended!");
        }
    }

    /**
     * Returns a new {@link CacheDataSource} instance. If {@code offline} is true, it can only read
     * data from the cache.
     */
    public CacheDataSource buildPriorityCacheDataSource(boolean offline) {
        DataSource cacheReadDataSource =  new FileDataSource();
        if (offline) {
            return new CacheDataSource(getDownloadCache(), DummyDataSource.INSTANCE,
                    cacheReadDataSource, null, CacheDataSource.FLAG_BLOCK_ON_CACHE, null);
        } else {
            DefaultDataSourceFactory upstreamFactory =
                    new DefaultDataSourceFactory(PreCacheProgressivePlayerActivity.this, buildHttpDataSourceFactory());
            DataSink cacheWriteDataSink = new CacheDataSink(getDownloadCache(), CacheDataSource.DEFAULT_MAX_CACHE_FILE_SIZE);
            DataSource upstream = upstreamFactory.createDataSource();
            upstream = mPriorityTaskManager == null ? upstream
                    : new PriorityDataSource(upstream, mPriorityTaskManager, C.PRIORITY_DOWNLOAD);
            Log.e("progressive_download","Create priority upstream data source!");
            return new CacheDataSource(getDownloadCache(), upstream, cacheReadDataSource,
                    cacheWriteDataSink, CacheDataSource.FLAG_BLOCK_ON_CACHE, null);
        }
    }

    /** Returns a new DataSource factory. */
    private DataSource.Factory buildDataSourceFactory() {
        DefaultDataSourceFactory upstreamFactory =
                new DefaultDataSourceFactory(this, buildHttpDataSourceFactory());
        return buildReadOnlyCacheDataSource(upstreamFactory, getDownloadCache());
//        return buildCacheDataSource(upstreamFactory,getDownloadCache());
    }

    private CacheDataSourceFactory buildCacheDataSource(DefaultDataSourceFactory upstreamFactory, Cache cache){
        return new CacheDataSourceFactory(cache, upstreamFactory);
    }


    private CacheDataSourceFactory buildReadOnlyCacheDataSource(DefaultDataSourceFactory upstreamFactory, Cache cache) {
        return new CacheDataSourceFactory(
                cache,
                upstreamFactory,
                new FileDataSourceFactory(),
                /* cacheWriteDataSinkFactory= */ null,
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
                /* eventListener= */ null);
    }

    private synchronized Cache getDownloadCache() {
        downloadCache = ((DemoApplication)getApplication()).getDownloadCache();
        return downloadCache;
    }


    public HttpDataSource.Factory buildHttpDataSourceFactory() {
        userAgent = Util.getUserAgent(this, "ExoPlayerDemo");
        return new DefaultHttpDataSourceFactory(userAgent);
    }


    private MediaSource buildMediaSource(Uri uri, @Nullable String overrideExtension) {
        @C.ContentType int type = Util.inferContentType(uri, overrideExtension);
        switch (type) {
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(uri);
            case C.TYPE_OTHER:
                return new ExtractorMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(uri);
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        playbackException = error;
    }
    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (playbackState == Player.STATE_ENDED
                || (playbackState == Player.STATE_IDLE && playbackException != null)) {
            player.release();
        }
    }
    @Override
    public void onPause() {
        super.onPause();
        releasePlayer();
    }

    private void releasePlayer() {
        if (updateCounterHandler != null){
            updateCounterHandler.removeCallbacksAndMessages(null);
        }
        if (player != null) {
            player.release();
            player = null;
        }

    }

    // Activity input

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // See whether the player view wants to handle media or DPAD keys events.
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

}
