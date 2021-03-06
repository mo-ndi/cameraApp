package org.opencv.android;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup.LayoutParams;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.List;

import static android.hardware.Camera.getCameraInfo;
import static android.hardware.Camera.getNumberOfCameras;

/**
 * This class is an implementation of the Bridge View between OpenCV and Java Camera.
 * This class relays on the functionality available in base class and only implements
 * required functions:
 * connectCamera - opens Java camera and sets the PreviewCallback to be delivered.
 * disconnectCamera - closes the camera and stops preview.
 * When frame is delivered via callback from Camera - it processed via OpenCV to be
 * converted to RGBA32 and then passed to the external callback for modifications if required.
 */
public class BetterJavaCameraView extends CameraBridgeViewBase implements PreviewCallback {

    private static final int MAGIC_TEXTURE_ID = 10;
    private static final String TAG = "JavaCameraView";

    private byte mBuffer[];
    private Mat[] mFrameChain;
    private int mChainIdx = 0;
    private Thread mThread;
    private boolean mStopThread;

    protected Camera mCamera;
    protected JavaCameraFrame[] mCameraFrame;
    private SurfaceTexture mSurfaceTexture;

    public static class JavaCameraSizeAccessor implements ListItemAccessor {

        @Override
        public int getWidth(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.width;
        }

        @Override
        public int getHeight(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.height;
        }
    }

    public BetterJavaCameraView(Context context, int cameraId) {
        super(context, cameraId);
    }

    public BetterJavaCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    int getFrontCameraId() {
        Camera.CameraInfo ci = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, ci);
            if (ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
        }
        return -1; // No front-facing camera found
    }

    int getBackCameraId() {
        Camera.CameraInfo ci = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, ci);
            if (ci.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return -1; // No front-facing camera found
    }

    protected boolean initializeCamera(int width, int height) {
        Log.d(TAG, "Initialize java camera");
        boolean result = true;
        synchronized (this) {
            mCamera = null;

            if (mCameraIndex == CAMERA_ID_ANY) {
                tryToOpenBackFacingCamera();
                tryToOpenAnyCamera();
            } else if (versionHigherThanGingerbread()) {
                tryingToOpenCamera(mCameraIndex);
            }

            if (mCamera == null) {
                Log.i(TAG, "Failed to open camera ");

                return false;
            }

            /* Now set camera parameters */
            result = initCameraParameters(width, height, result);
        }

        return result;
    }

    private boolean initCameraParameters(int width, int height, boolean result) {
        try {
            Camera.Parameters params = mCamera.getParameters();
            Log.d(TAG, "getSupportedPreviewSizes()");
            List<Camera.Size> sizes = params.getSupportedPreviewSizes();

            if (sizes != null) {
                /* Select the size that fits surface considering maximum size allowed */
                setPreviewFormat(params);
                setPreviewSize(width, height, params, sizes);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH && !Build.MODEL.equals("GT-I9100"))
                    params.setRecordingHint(true);

                List<String> FocusModes = params.getSupportedFocusModes();
                if (FocusModes != null && FocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                }

                mCamera.setParameters(params);
                params = mCamera.getParameters();

                mFrameWidth = params.getPreviewSize().width;
                mFrameHeight = params.getPreviewSize().height;
                Log.d(TAG, "mFrameWidth: "+mFrameWidth);
                Log.d(TAG, "mFrameHeight: "+mFrameHeight);

                if ((getLayoutParams().width == LayoutParams.MATCH_PARENT) && (getLayoutParams().height == LayoutParams.MATCH_PARENT))
                    mScale = Math.min(((float) height) / mFrameHeight, ((float) width) / mFrameWidth);
                else
                    mScale = 0;

                if (mFpsMeter != null) {
                    mFpsMeter.setResolution(mFrameWidth, mFrameHeight);
                }

                int size = mFrameWidth * mFrameHeight;
                size = size * ImageFormat.getBitsPerPixel(params.getPreviewFormat()) / 8;
                mBuffer = new byte[size];

                mCamera.addCallbackBuffer(mBuffer);
                mCamera.setPreviewCallbackWithBuffer(this);

                mFrameChain = new Mat[2];
                mFrameChain[0] = new Mat(mFrameHeight + (mFrameHeight / 2), mFrameWidth, CvType.CV_8UC1);
                mFrameChain[1] = new Mat(mFrameHeight + (mFrameHeight / 2), mFrameWidth, CvType.CV_8UC1);

                AllocateCache();

                mCameraFrame = new JavaCameraFrame[2];
                mCameraFrame[0] = new JavaCameraFrame(mFrameChain[0], mFrameWidth, mFrameHeight);
                mCameraFrame[1] = new JavaCameraFrame(mFrameChain[1], mFrameWidth, mFrameHeight);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    mSurfaceTexture = new SurfaceTexture(MAGIC_TEXTURE_ID);
                    mCamera.setPreviewTexture(mSurfaceTexture);
                } else
                    mCamera.setPreviewDisplay(null);

                /* Finally we are ready to start the preview */
                Log.d(TAG, "startPreview");
                //mCamera.setDisplayOrientation(90);
                Log.d(TAG, "displayOrientation: ");
                mCamera.startPreview();
            } else
                result = false;
        } catch (Exception e) {
            result = false;
            e.printStackTrace();
        }
        return result;
    }

    private void setPreviewSize(int width, int height, Camera.Parameters params, List<Camera.Size> sizes) {
        Size frameSize = calculateCameraFrameSize(sizes, new JavaCameraSizeAccessor(), width, height);
        params.setPreviewSize((int) frameSize.width, (int) frameSize.height);
    }

    private void setPreviewFormat(Camera.Parameters params) {
        params.setPreviewFormat(ImageFormat.NV21);
        Log.d(TAG, "Build.BRAND: " + Build.BRAND);
        if (Build.BRAND.toLowerCase().startsWith("generic")) {
            params.setPreviewFormat(ImageFormat.YV12);
            Log.d(TAG, "Check seting YV12");
        }
    }

    private void tryingToOpenCamera(int mCameraIndex) {
        Log.i(TAG, "Trying to open camera with index: " + mCameraIndex);
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        int cameraFacing = getCameraFacing(mCameraIndex);
        for (int camIdx = 0; camIdx < getNumberOfCameras(); ++camIdx) {
            getCameraInfo(camIdx, cameraInfo);
            if (cameraInfo.facing == cameraFacing) {
                openCamera(camIdx);
                break;
            }
        }
    }

    private int getCameraFacing(int mCameraIndex) {
        int cameraFacing;
        if (mCameraIndex == CAMERA_ID_BACK) {
            cameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
        } else if (mCameraIndex == CAMERA_ID_FRONT) {
            cameraFacing = Camera.CameraInfo.CAMERA_FACING_FRONT;
        } else {
            throw new RuntimeException("Camera index not found");
        }
        return cameraFacing;
    }

    private void openCamera(int localCameraIndex) {
        Log.d(TAG, "Trying to open camera with new open(" + Integer.valueOf(localCameraIndex) + ")");
        try {
            mCamera = Camera.open(localCameraIndex);
        } catch (RuntimeException e) {
            Log.e(TAG, "Camera #" + localCameraIndex + "failed to open: " + e.getLocalizedMessage());
        }
    }

    private boolean versionHigherThanGingerbread() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD;
    }

    private void tryToOpenAnyCamera() {
        if (mCamera == null && versionHigherThanGingerbread()) {
            for (int camIdx = 0; camIdx < getNumberOfCameras(); ++camIdx) {
                Log.d(TAG, "Trying to open camera with new open(" + Integer.valueOf(camIdx) + ")");
                try {
                    mCamera = Camera.open(camIdx);
                    break;
                } catch (RuntimeException e) {
                    Log.e(TAG, "Camera #" + camIdx + "failed to open: " + e.getLocalizedMessage());
                }
            }
        }
    }

    private void tryToOpenBackFacingCamera() {
        Log.d(TAG, "Trying to open camera with old open()");
        try {
            mCamera = Camera.open();
        } catch (Exception e) {
            Log.e(TAG, "Camera is not available (in use or does not exist): " + e.getLocalizedMessage());
        }
    }

    protected void releaseCamera() {
        synchronized (this) {
            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.setPreviewCallback(null);

                mCamera.release();
            }
            mCamera = null;
            if (mFrameChain != null) {
                mFrameChain[0].release();
                mFrameChain[1].release();
            }
            if (mCameraFrame != null) {
                mCameraFrame[0].release();
                mCameraFrame[1].release();
            }
        }
    }

    private boolean mCameraFrameReady = false;

    @Override
    protected boolean connectCamera(int width, int height) {

        /* 1. We need to instantiate camera
         * 2. We need to start thread which will be getting frames
         */
        /* First step - initialize camera connection */
        Log.d(TAG, "Connecting to camera");
        if (!initializeCamera(width, height))
            return false;

        mCameraFrameReady = false;

        /* now we can start update thread */
        Log.d(TAG, "Starting processing thread");
        mStopThread = false;
        mThread = new Thread(new CameraWorker());
        mThread.start();

        return true;
    }

    @Override
    protected void disconnectCamera() {
        /* 1. We need to stop thread which updating the frames
         * 2. Stop camera and release it
         */
        Log.d(TAG, "Disconnecting from camera");
        try {
            mStopThread = true;
            Log.d(TAG, "Notify thread");
            synchronized (this) {
                this.notify();
            }
            Log.d(TAG, "Wating for thread");
            if (mThread != null)
                mThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            mThread = null;
        }

        /* Now release camera */
        releaseCamera();

        mCameraFrameReady = false;
    }

    @Override
    public void onPreviewFrame(byte[] frame, Camera arg1) {
        synchronized (this) {
            mFrameChain[mChainIdx].put(0, 0, frame);
            mCameraFrameReady = true;
            this.notify();
        }
        if (mCamera != null)
            mCamera.addCallbackBuffer(mBuffer);
    }

    private class JavaCameraFrame implements CvCameraViewFrame {
        @Override
        public Mat gray() {
            return mYuvFrameData.submat(0, mHeight, 0, mWidth);
        }

        @Override
        public Mat rgba() {
            Camera.Parameters parameters = mCamera.getParameters();
            int previewFormat = parameters.getPreviewFormat();
            if (previewFormat == ImageFormat.NV21) {
                Imgproc.cvtColor(mYuvFrameData, mRgba, Imgproc.COLOR_YUV2RGBA_NV21, 4);
            } else if (previewFormat == ImageFormat.YV12) {
                Imgproc.cvtColor(mYuvFrameData, mRgba, Imgproc.COLOR_YUV2RGB_I420, 4);  // COLOR_YUV2RGBA_YV12 produces inverted colors
            }
            return mRgba;
        }

        public JavaCameraFrame(Mat Yuv420sp, int width, int height) {
            super();
            mWidth = width;
            mHeight = height;
            mYuvFrameData = Yuv420sp;
            mRgba = new Mat();
        }

        public void release() {
            mRgba.release();
        }

        private Mat mYuvFrameData;
        private Mat mRgba;
        private int mWidth;
        private int mHeight;
    }

    private class CameraWorker implements Runnable {

        @Override
        public void run() {
            do {
                synchronized (BetterJavaCameraView.this) {
                    try {
                        while (!mCameraFrameReady && !mStopThread) {
                            BetterJavaCameraView.this.wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (mCameraFrameReady)
                        mChainIdx = 1 - mChainIdx;
                }

                if (!mStopThread && mCameraFrameReady) {
                    mCameraFrameReady = false;
                    Mat mat = mFrameChain[1 - mChainIdx];
                    if (!mat.empty()) {
                        //Log.d(TAG, "mChainIdx:" + mChainIdx);
                        deliverAndDrawFrame(mCameraFrame[1 - mChainIdx]);
                    }
                }
            } while (!mStopThread);
            Log.d(TAG, "Finish processing thread");
        }
    }
}
