/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.sharry.lib.gif;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Thanks for Google
 * <p>
 * Copy from
 * http://androidxref.com/9.0.0_r3/xref/frameworks/ex/framesequence/src/android/support/rastermill/FrameSequenceDrawable.java
 * <p>
 * Add downsampleing function.
 *
 * @author Sharry <a href="xiaoyu.zhu@1hai.cn">Contact me.</a>
 * @version 1.0
 * @since 2019-12-20 17:48
 */
public class FrameSequenceDrawable extends Drawable implements Animatable, Runnable {

    // ///////////////////////////////////////////////  Class define //////////////////////////////////////////////////////

    private static final String TAG = "FrameSequence";

    /**
     * These constants are chosen to imitate common browser behavior for WebP/GIF.
     * If other decoders are added, this behavior should be moved into the WebP/GIF decoders.
     * <p>
     * Note that 0 delay is undefined behavior in the GIF standard.
     */
    private static final long MIN_DELAY_MS = 20;
    private static final long DEFAULT_DELAY_MS = 100;
    private static final BitmapProvider DEFAULT_BITMAP_PROVIDER = new BitmapProvider() {
        @Override
        public Bitmap acquireBitmap(int minWidth, int minHeight) {
            return Bitmap.createBitmap(minWidth, minHeight, Bitmap.Config.ARGB_8888);
        }

        @Override
        public void releaseBitmap(Bitmap bitmap) {
        }
    };

    /**
     * Loop a finite number of times, which can be set using setLoopCount. Default to loop once.
     */
    public static final int LOOP_FINITE = 1;

    /**
     * Loop continuously. The OnFinishedListener will never be called.
     */
    public static final int LOOP_INF = 2;

    /**
     * Use loop count stored in source data, or LOOP_ONCE if not present.
     */
    public static final int LOOP_DEFAULT = 3;

    /**
     * Loop only once.
     *
     * @deprecated Use LOOP_FINITE instead.
     */
    @Deprecated
    public static final int LOOP_ONCE = LOOP_FINITE;

    // Status.
    private static final int STATE_SCHEDULED = 1;
    private static final int STATE_DECODING = 2;
    private static final int STATE_WAITING_TO_SWAP = 3;
    private static final int STATE_READY_TO_SWAP = 4;

    private static HandlerThread sDecodingThread;
    private static Handler sDecodingThreadHandler;

    private static synchronized void initializeDecodingThread() {
        if (sDecodingThread != null) {
            return;
        }
        sDecodingThread = new HandlerThread("FrameSequence decoding thread",
                Process.THREAD_PRIORITY_BACKGROUND);
        sDecodingThread.start();
        sDecodingThreadHandler = new Handler(sDecodingThread.getLooper());
    }

    private static Bitmap acquireAndValidateBitmap(BitmapProvider bitmapProvider,
                                                   int minWidth, int minHeight) {
        Bitmap bitmap = bitmapProvider.acquireBitmap(minWidth, minHeight);

        if (bitmap.getWidth() < minWidth
                || bitmap.getHeight() < minHeight
                || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            throw new IllegalArgumentException("Invalid bitmap provided");
        }

        return bitmap;
    }

    // ///////////////////////////////////////////////  Object define //////////////////////////////////////////////////////

    private final GifDecoder mDecoder;
    private final int mInSampleSize;

    private final Paint mPaint;
    private BitmapShader mFrontBitmapShader;
    private BitmapShader mBackBitmapShader;
    private final Rect mSrcRect;
    private boolean mCircleMaskEnabled;

    //Protects the fields below
    private final Object mLock = new Object();

    private final BitmapProvider mBitmapProvider;
    private boolean mDestroyed = false;
    private Bitmap mFrontBitmap;
    private Bitmap mBackBitmap;

    private int mState;
    private int mCurrentLoop;
    private int mLoopBehavior = LOOP_DEFAULT;
    private int mLoopCount = 1;

    private long mLastSwap;
    private long mNextSwap;
    private int mNextFrameToDecode;
    private OnFinishedListener mOnFinishedListener;

    private final RectF mTempRectF = new RectF();

    /**
     * Runs on decoding thread, only modifies mBackBitmap's pixels
     */
    private final Runnable mDecodeRunnable = new Runnable() {
        @Override
        public void run() {
            int nextFrame;
            Bitmap bitmap;
            synchronized (mLock) {
                if (mDestroyed) {
                    return;
                }
                nextFrame = mNextFrameToDecode;
                if (nextFrame < 0) {
                    return;
                }
                bitmap = mBackBitmap;
                mState = STATE_DECODING;
            }
            int lastFrame = nextFrame - 2;
            boolean exceptionDuringDecode = false;
            long invalidateTimeMs = 0;
            try {
                invalidateTimeMs = mDecoder.getFrame(nextFrame, bitmap, lastFrame, mInSampleSize);
            } catch (Exception e) {
                // Exception during decode: continue, but delay next frame indefinitely.
                Log.e(TAG, "exception during decode: " + e);
                exceptionDuringDecode = true;
            }

            if (invalidateTimeMs < MIN_DELAY_MS) {
                invalidateTimeMs = DEFAULT_DELAY_MS;
            }

            boolean schedule = false;
            Bitmap bitmapToRelease = null;
            synchronized (mLock) {
                if (mDestroyed) {
                    bitmapToRelease = mBackBitmap;
                    mBackBitmap = null;
                } else if (mNextFrameToDecode >= 0 && mState == STATE_DECODING) {
                    schedule = true;
                    mNextSwap = exceptionDuringDecode ? Long.MAX_VALUE : invalidateTimeMs + mLastSwap;
                    mState = STATE_WAITING_TO_SWAP;
                }
            }
            if (schedule) {
                scheduleSelf(FrameSequenceDrawable.this, mNextSwap);
            }
            if (bitmapToRelease != null) {
                // destroy the bitmap here, since there's no safe way to get back to
                // drawable thread - drawable is likely detached, so schedule is noop.
                mBitmapProvider.releaseBitmap(bitmapToRelease);
            }
        }
    };

    private final Runnable mFinishedCallbackRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                mNextFrameToDecode = -1;
                mState = 0;
            }
            if (mOnFinishedListener != null) {
                mOnFinishedListener.onFinished(FrameSequenceDrawable.this);
            }
        }
    };

    public FrameSequenceDrawable(GifDecoder decoder) {
        this(decoder, DEFAULT_BITMAP_PROVIDER, 1);
    }

    public FrameSequenceDrawable(GifDecoder decoder, BitmapProvider bitmapProvider, int inSampleSize) {
        if (decoder == null || bitmapProvider == null) {
            throw new IllegalArgumentException();
        }
        mDecoder = decoder;
        mInSampleSize = inSampleSize;
        mBitmapProvider = bitmapProvider;
        final int width = decoder.getWidth() / inSampleSize;
        final int height = decoder.getHeight() / inSampleSize;
        mFrontBitmap = acquireAndValidateBitmap(bitmapProvider, width, height);
        mBackBitmap = acquireAndValidateBitmap(bitmapProvider, width, height);
        mSrcRect = new Rect(0, 0, width, height);
        mPaint = new Paint();
        mPaint.setFilterBitmap(true);

        mFrontBitmapShader
                = new BitmapShader(mFrontBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        mBackBitmapShader
                = new BitmapShader(mBackBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);

        mLastSwap = 0;

        mNextFrameToDecode = -1;
        mDecoder.getFrame(0, mFrontBitmap, -1, mInSampleSize);
        initializeDecodingThread();
    }

    /**
     * Define looping behavior of frame sequence.
     * <p>
     * Must be one of LOOP_ONCE, LOOP_INF, LOOP_DEFAULT, or LOOP_FINITE.
     */
    public void setLoopBehavior(int loopBehavior) {
        mLoopBehavior = loopBehavior;
    }

    /**
     * Set the number of loops in LOOP_FINITE mode. The number must be a postive integer.
     */
    public void setLoopCount(int loopCount) {
        mLoopCount = loopCount;
    }

    /**
     * Register a callback to be invoked when a FrameSequenceDrawable finishes looping.
     *
     * @see #setLoopBehavior(int)
     */
    public void setOnFinishedListener(OnFinishedListener onFinishedListener) {
        mOnFinishedListener = onFinishedListener;
    }


    /**
     * Pass true to mask the shape of the animated drawing content to a circle.
     *
     * <p> The masking circle will be the largest circle contained in the Drawable's bounds.
     * Masking is done with BitmapShader, incurring minimal additional draw cost.
     */
    public final void setCircleMaskEnabled(boolean circleMaskEnabled) {
        if (mCircleMaskEnabled != circleMaskEnabled) {
            mCircleMaskEnabled = circleMaskEnabled;
            // Anti alias only necessary when using circular mask
            mPaint.setAntiAlias(circleMaskEnabled);
            invalidateSelf();
        }
    }

    public final boolean getCircleMaskEnabled() {
        return mCircleMaskEnabled;
    }

    private void checkDestroyedLocked() {
        if (mDestroyed) {
            throw new IllegalStateException("Cannot perform operation on recycled drawable");
        }
    }

    public boolean isDestroyed() {
        synchronized (mLock) {
            return mDestroyed;
        }
    }

    /**
     * Marks the drawable as permanently recycled (and thus unusable), and releases any owned
     * Bitmaps drawable to its BitmapProvider, if attached.
     * <p>
     * If no BitmapProvider is attached to the drawable, recycle() is called on the Bitmaps.
     */
    public void destroy() {
        if (mBitmapProvider == null) {
            throw new IllegalStateException("BitmapProvider must be non-null");
        }

        Bitmap bitmapToReleaseA;
        Bitmap bitmapToReleaseB = null;
        synchronized (mLock) {
            checkDestroyedLocked();

            bitmapToReleaseA = mFrontBitmap;
            mFrontBitmap = null;

            if (mState != STATE_DECODING) {
                bitmapToReleaseB = mBackBitmap;
                mBackBitmap = null;
            }

            mDestroyed = true;
        }

        // For simplicity and safety, we don't destroy the state object here
        mBitmapProvider.releaseBitmap(bitmapToReleaseA);
        if (bitmapToReleaseB != null) {
            mBitmapProvider.releaseBitmap(bitmapToReleaseB);
        }
    }

    // ///////////////////////////////////////////////  Drawable impl //////////////////////////////////////////////////////

    @Override
    public void draw(@NonNull Canvas canvas) {
        synchronized (mLock) {
            checkDestroyedLocked();
            if (mState == STATE_WAITING_TO_SWAP) {
                // may have failed to schedule mark ready runnable,
                // so go ahead and swap if swapping is due
                if (mNextSwap - SystemClock.uptimeMillis() <= 0) {
                    mState = STATE_READY_TO_SWAP;
                }
            }

            if (isRunning() && mState == STATE_READY_TO_SWAP) {
                // Because draw has occurred, the view system is guaranteed to no longer hold a
                // reference to the old mFrontBitmap, so we now use it to produce the next frame
                Bitmap tmp = mBackBitmap;
                mBackBitmap = mFrontBitmap;
                mFrontBitmap = tmp;

                BitmapShader tmpShader = mBackBitmapShader;
                mBackBitmapShader = mFrontBitmapShader;
                mFrontBitmapShader = tmpShader;

                mLastSwap = SystemClock.uptimeMillis();

                boolean continueLooping = true;
                if (mNextFrameToDecode == mDecoder.getFrameCount() - 1) {
                    mCurrentLoop++;
                    if ((mLoopBehavior == LOOP_FINITE && mCurrentLoop == mLoopCount) ||
                            (mLoopBehavior == LOOP_DEFAULT && mCurrentLoop == mDecoder.getLooperCount())) {
                        continueLooping = false;
                    }
                }

                if (continueLooping) {
                    scheduleDecodeLocked();
                } else {
                    scheduleSelf(mFinishedCallbackRunnable, 0);
                }
            }
        }

        if (mCircleMaskEnabled) {
            final Rect bounds = getBounds();
            final int bitmapWidth = getIntrinsicWidth();
            final int bitmapHeight = getIntrinsicHeight();
            final float scaleX = 1.0f * bounds.width() / bitmapWidth;
            final float scaleY = 1.0f * bounds.height() / bitmapHeight;

            canvas.save();
            // scale and translate to account for bounds, so we can operate in intrinsic
            // width/height (so it's valid to use an unscaled bitmap shader)
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(scaleX, scaleY);

            final float unscaledCircleDiameter = Math.min(bounds.width(), bounds.height());
            final float scaledDiameterX = unscaledCircleDiameter / scaleX;
            final float scaledDiameterY = unscaledCircleDiameter / scaleY;

            // Want to draw a circle, but we have to compensate for canvas scale
            mTempRectF.set(
                    (bitmapWidth - scaledDiameterX) / 2.0f,
                    (bitmapHeight - scaledDiameterY) / 2.0f,
                    (bitmapWidth + scaledDiameterX) / 2.0f,
                    (bitmapHeight + scaledDiameterY) / 2.0f);
            mPaint.setShader(mFrontBitmapShader);
            canvas.drawOval(mTempRectF, mPaint);
            canvas.restore();
        } else {
            mPaint.setShader(null);
            canvas.drawBitmap(mFrontBitmap, mSrcRect, getBounds(), mPaint);
        }
    }

    @Override
    public void unscheduleSelf(Runnable what) {
        synchronized (mLock) {
            mNextFrameToDecode = -1;
            mState = 0;
        }
        super.unscheduleSelf(what);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);

        if (!visible) {
            stop();
        } else if (restart || changed) {
            stop();
            start();
        }

        return changed;
    }

    // drawing properties

    @Override
    public void setFilterBitmap(boolean filter) {
        mPaint.setFilterBitmap(filter);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getIntrinsicWidth() {
        return mDecoder.getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mDecoder.getHeight();
    }

    @Override
    public int getOpacity() {
        return mDecoder.isOpaque() ? PixelFormat.OPAQUE : PixelFormat.TRANSPARENT;
    }

    private void scheduleDecodeLocked() {
        mState = STATE_SCHEDULED;
        mNextFrameToDecode = (mNextFrameToDecode + 1) % mDecoder.getFrameCount();
        sDecodingThreadHandler.post(mDecodeRunnable);
    }

    // ///////////////////////////////////////////////  Runnable impl //////////////////////////////////////////////////////

    @Override
    public void run() {
        // set ready to swap as necessary
        boolean invalidate = false;
        synchronized (mLock) {
            if (mNextFrameToDecode >= 0 && mState == STATE_WAITING_TO_SWAP) {
                mState = STATE_READY_TO_SWAP;
                invalidate = true;
            }
        }
        if (invalidate) {
            invalidateSelf();
        }
    }

    // ///////////////////////////////////////////////  Animatable impl //////////////////////////////////////////////////////

    @Override
    public void start() {
        if (!isRunning()) {
            synchronized (mLock) {
                checkDestroyedLocked();
                if (mState == STATE_SCHEDULED) {
                    return; // already scheduled
                }
                mCurrentLoop = 0;
                scheduleDecodeLocked();
            }
        }
    }

    @Override
    public void stop() {
        if (isRunning()) {
            unscheduleSelf(this);
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (mLock) {
            return mNextFrameToDecode > -1 && !mDestroyed;
        }
    }


    // ///////////////////////////////////////////////  Object impl //////////////////////////////////////////////////////

    @Override
    protected void finalize() throws Throwable {
        try {
            mDecoder.destroy();
        } finally {
            super.finalize();
        }
    }

    // ///////////////////////////////////////////////  Interface define //////////////////////////////////////////////////////

    public interface OnFinishedListener {
        /**
         * Called when a FrameSequenceDrawable has finished looping.
         * <p>
         * Note that this is will not be called if the drawable is explicitly
         * stopped, or marked invisible.
         */
        void onFinished(FrameSequenceDrawable drawable);
    }

    public interface BitmapProvider {
        /**
         * Called by FrameSequenceDrawable to aquire an 8888 Bitmap with minimum dimensions.
         */
        Bitmap acquireBitmap(int minWidth, int minHeight);

        /**
         * Called by FrameSequenceDrawable to release a Bitmap it no longer needs. The Bitmap
         * will no longer be used at all by the drawable, so it is safe to reuse elsewhere.
         * <p>
         * This method may be called by FrameSequenceDrawable on any thread.
         */
        void releaseBitmap(Bitmap bitmap);
    }

}
