package com.arcore.ruler;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class LocateActivity extends Activity {
    private static final String TAG = LocateActivity.class.getSimpleName();
    private boolean mUserRequestedInstall = true;

    private TextView locate_rotate;
    private TextView locate_scale;


    private TextView mTextView;
    private GLSurfaceView mSurfaceView;

    private MainRenderer mRenderer;

    private Session mSession;
    private Config mConfig;

    // variable representing the selected coordinates
    private float mCurrentX;
    private float mCurrentY;

    private float mScaleFactor = 0.02f;
    private float mRotateFactor = 0.0f;

    private final int TABLE = 0;
    private final int CHAIR = 1;
    private final int BED = 2;


    // determines whether the object is rotating + a variable that determines which object is selected
    private int mSelectedModel = -1;

    private float[] mModelMatrix = new float[16];

    private float[] mTableModelMatrix = new float[16];
    private float[] mChairModelMatrix = new float[16];
    private float[] mBedModelMatrix = new float[16];

    private boolean[] mModelInit = { false, false, false };
    private boolean[] mModelPut = { false, false, false }; // whether the model is placed



    // Switch variable to trigger on double tap. Positioning if false x Positioning if true
    private boolean mIsPut = false;

    private GestureDetector mGestureDetector; // furniture rotation variable

    private ScaleGestureDetector mScaleDetector; // furniture resizing variable


    private Button btn_capture_locate;

        //save Check
        private Boolean isSaveClick = false;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            hideStatusBarAndTitleBar();
            setContentView(R.layout.activity_locate);

        mTextView = (TextView) findViewById(R.id.txt_locate);
        mSurfaceView = (GLSurfaceView) findViewById(R.id.gl_surface_view);

        locate_rotate = (TextView) findViewById(R.id.locate_rotate);
        locate_scale = (TextView) findViewById(R.id.locate_scale);


        final DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    synchronized (this) {
                        mRenderer.onDisplayChanged();
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }
            }, null);

            mGestureDetector = new GestureDetector(this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onSingleTapUp(MotionEvent event) {
                            mCurrentX = event.getX();
                            mCurrentY = event.getY();

                            return true;
                        }
                        @Override
                        public boolean onDoubleTap(MotionEvent event) {
// mIsPut comes in false
                            mCurrentX = event.getX();
                            mCurrentY = event.getY();
                            mIsPut = true;

                            return true;
                        }
                        @Override
                        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                                float distanceX, float distanceY) {

// The direction of the object is adjusted in real time
                            if (mSelectedModel != -1) {
                                mRotateFactor -= (distanceX / 10);
                                mRotateFactor%=360;
                                if(mRotateFactor<0){
                                    mRotateFactor += 360;
                                }
                                Matrix.rotateM(mModelMatrix, 0, -distanceX / 10, 0.0f, 1.0f, 0.0f);
                                String rotateFactor = String.format(Locale.getDefault(), "Direction:% d degrees", (int)mRotateFactor);
                                locate_rotate.setText(rotateFactor);
                            }
                            return true;
                        }
                    });
            mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
// size can be adjusted in real time
                            if (mSelectedModel != -1) {
                                mScaleFactor *= detector.getScaleFactor();
                                String scaleFactor = String.format(Locale.getDefault(), "\n" +
                                        "Size:% .2f Magnification", mScaleFactor*100 );
                                locate_scale.setText(scaleFactor);

                                Matrix.scaleM(mModelMatrix, 0,
                                        detector.getScaleFactor(),
                                        detector.getScaleFactor(),
                                        detector.getScaleFactor());
                            }
                            return true;
                        }
                    });
        }

        mRenderer = new MainRenderer(this, new MainRenderer.RenderCallback(){
            @Override
            public void preRender() throws CameraNotAvailableException {
                if (mRenderer.isViewportChanged()) {
                    Display display = getWindowManager().getDefaultDisplay();
                    int displayRotation = display.getRotation();
                    mRenderer.updateSession(mSession, displayRotation);
                }

                mSession.setCameraTextureName(mRenderer.getTextureId());

                Frame frame = mSession.update();
                if (frame.hasDisplayGeometryChanged()) {
                    mRenderer.transformDisplayGeometry(frame);
                }

                PointCloud pointCloud = frame.acquirePointCloud();
                mRenderer.updatePointCloud(pointCloud);
                pointCloud.release();

                Collection<Plane> planes = mSession.getAllTrackables(Plane.class);
                for(Plane plane : planes){
                    if(plane.getTrackingState() == TrackingState.TRACKING
                            && plane.getSubsumedBy() == null){
                        mRenderer.updatePlane(plane);
                    }
                }

                final Camera camera = frame.getCamera();
                float[] projMatrix = new float[16];
                camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f);
                float[] viewMatrix = new float[16];
                camera.getViewMatrix(viewMatrix, 0);

                switch (mSelectedModel) {
                    case TABLE:
                        if (!mModelInit[TABLE]) {
                            float position[] = calculateInitialPosition(mRenderer.getWidth(), mRenderer.getHeight(), projMatrix, viewMatrix);

                            Matrix.setIdentityM(mModelMatrix, 0);// create unit matrix. Create a matrix-> the ability to create an object
                            Matrix.translateM(mModelMatrix, 0, position[0], position[1], position[2]); // translation matrix: translation by position
                            Matrix.scaleM(mModelMatrix, 0, 0.02f, 0.02f, 0.02f); // Artificially captures the initial size of objects in their position

                            mModelInit[TABLE] = true;
                            mModelPut[TABLE] = false;
                        }
                        if (!mModelPut[TABLE]) {
                            mRenderer.setTableModelMatrix(mModelMatrix);
                        }
                        mRenderer.setModelDraw(true, mModelPut[CHAIR], mModelPut[BED]);
                        if (mModelInit[CHAIR] && !mModelPut[CHAIR]) { //init:true, put:false 일 시
                            mModelInit[CHAIR] = false;
                        }
                        if (mModelInit[BED] && !mModelPut[BED]) {
                            mModelInit[BED] = false;
                        }
                        break;
                    case CHAIR:
                        if (!mModelInit[CHAIR]) {
                            float position[] = calculateInitialPosition(mRenderer.getWidth(),
                                    mRenderer.getHeight(), projMatrix, viewMatrix);

                            Matrix.setIdentityM(mModelMatrix, 0);
                            Matrix.translateM(mModelMatrix, 0, position[0], position[1], position[2]);
                            Matrix.scaleM(mModelMatrix, 0, 0.02f, 0.02f, 0.02f);

                            mModelInit[CHAIR] = true;
                            mModelPut[CHAIR] = false;
                        }
                        if (!mModelPut[CHAIR]) {
                            mRenderer.setChairModelMatrix(mModelMatrix);
                        }
                        mRenderer.setModelDraw(mModelPut[TABLE], true, mModelPut[BED]);

                        if (mModelInit[TABLE] && !mModelPut[TABLE]) {
                            mModelInit[TABLE] = false;
                        }
                        if (mModelInit[BED] && !mModelPut[BED]) {
                            mModelInit[BED] = false;
                        }
                        break;
                    case BED:
                        if (!mModelInit[BED]) {
                            float position[] = calculateInitialPosition(mRenderer.getWidth(),
                                    mRenderer.getHeight(), projMatrix, viewMatrix);

                            Matrix.setIdentityM(mModelMatrix, 0);
                            Matrix.translateM(mModelMatrix, 0, position[0], position[1], position[2]);
                            Matrix.scaleM(mModelMatrix, 0, 0.02f, 0.02f, 0.02f);

                            mModelInit[BED] = true;
                            mModelPut[BED] = false;
                        }
                        if (!mModelPut[BED]) {
                            mRenderer.setBedModelMatrix(mModelMatrix);
                        }
                        mRenderer.setModelDraw(mModelPut[TABLE], mModelPut[CHAIR], true);

                        if (mModelInit[TABLE] && !mModelPut[TABLE]) {
                            mModelInit[TABLE] = false;
                        }
                        if (mModelInit[CHAIR] && !mModelPut[CHAIR]) {
                            mModelInit[CHAIR] = false;
                        }
                        break;
                    default:
                        break;
                }



// << when double tap event occurs >> mIsPut becomes true and the following event occurs
                if (mIsPut) {
                    List<HitResult> results = frame.hitTest(mCurrentX, mCurrentY);

// do HitTest to get the result If there is a plane, place the object there
                    for (HitResult result : results) {
                        Trackable trackable = result.getTrackable();
                        Pose pose = result.getHitPose();
// assign hit result to pose object
                        float[] modelMatrix = new float[16];
                        pose.toMatrix(modelMatrix, 0);


// Use a function to apply the specified direction and size.
                        Matrix.scaleM(modelMatrix, 0, mScaleFactor, mScaleFactor, mScaleFactor);// zoom matrix: zoom by a specified multiple
                        Matrix.rotateM(modelMatrix, 0, mRotateFactor, 0.0f, 1.0f, 0.0f); //Rotate by ratio (matrix, start point 0, rotation angle, rotation vector)

                        mScaleFactor = 0.02f;
                        if (trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(result.getHitPose())) {
                            switch (mSelectedModel) {
                                case TABLE:
                                    if (!mModelPut[TABLE]) {
                                        mModelPut[TABLE] = true;


// Function to prevent rotation and scale conversion. However, when commenting, it is placed in the desired position and disappears immediately
                                        mSelectedModel = -1;

                                        System.arraycopy(modelMatrix, 0, mTableModelMatrix, 0, 16);
                                        Matrix.setIdentityM(mModelMatrix, 0);

                                        mIsPut = false;

                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                mTextView.setText(getString(R.string.table_put));
                                            }
                                        });
                                        TimerTask textTask = new TimerTask() {
                                            @Override
                                            public void run() {
                                                runOnUiThread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        mTextView.setText(getString(R.string.not_selected));
                                                    }
                                                });
                                            }
                                        };
                                        Timer textTimer = new Timer();
                                        textTimer.schedule(textTask, 2000);
                                    }
                                    break;
                                case CHAIR:
                                    if (!mModelPut[CHAIR]) {
                                        mModelPut[CHAIR] = true;
                                        mSelectedModel = -1;
                                        System.arraycopy(modelMatrix, 0, mChairModelMatrix, 0, 16);
                                        Matrix.setIdentityM(mModelMatrix, 0);
                                        mIsPut = false;
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                mTextView.setText(getString(R.string.chair_put));
                                            }
                                        });
                                        TimerTask textTask = new TimerTask() {
                                            @Override
                                            public void run() {
                                                runOnUiThread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        mTextView.setText(getString(R.string.not_selected));
                                                    }
                                                });
                                            }
                                        };
                                        Timer textTimer = new Timer();
                                        textTimer.schedule(textTask, 2000);
                                    }
                                    break;
                                case BED:
                                    if (!mModelPut[BED]) {
                                        mModelPut[BED] = true;
                                        mSelectedModel = -1;
                                        System.arraycopy(modelMatrix, 0, mBedModelMatrix, 0, 16);
                                        Matrix.setIdentityM(mModelMatrix, 0);
                                        mIsPut = false;
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                mTextView.setText(getString(R.string.bed_put));
                                            }
                                        });
                                        TimerTask textTask = new TimerTask() {
                                            @Override
                                            public void run() {
                                                runOnUiThread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        mTextView.setText(getString(R.string.not_selected));
                                                    }
                                                });
                                            }
                                        };
                                        Timer textTimer = new Timer();
                                        textTimer.schedule(textTask, 2000);
                                    }
                                    break;
                            }
                        }
                        if (!mIsPut) {
                            break;
                        }
                    }
                    if (mIsPut) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mTextView.setText(getString(R.string.not_valid_position));
                            }
                        });
                        TimerTask textTask = new TimerTask() {
                            @Override
                            public void run() {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        switch (mSelectedModel) {
                                            case TABLE:
                                                mTextView.setText(getString(R.string.table_selected));
                                                break;
                                            case CHAIR:
                                                mTextView.setText(getString(R.string.chair_selected));
                                                break;
                                            case BED:
                                                mTextView.setText(getString(R.string.bed_selected));
                                                break;
                                            default:
                                                mTextView.setText(getString(R.string.not_selected));
                                                break;
                                        }
                                    }
                                });
                            }
                        };
                        Timer textTimer = new Timer();
                        textTimer.schedule(textTask, 2000);
                    }
                    mIsPut = false;
                }



// place the desired object in its place
                if (mModelPut[TABLE]) {
                    mRenderer.setTableModelMatrix(mTableModelMatrix);
                    mRenderer.updateTableViewMatrix(viewMatrix);
                    mModelInit[TABLE] = false;
                }
                if (mModelPut[CHAIR]) {
                    mRenderer.setChairModelMatrix(mChairModelMatrix);
                    mRenderer.updateChairViewMatrix(viewMatrix);
                    mModelInit[CHAIR] = false;
                }
                if (mModelPut[BED]) {
                    mRenderer.setBedModelMatrix(mBedModelMatrix);
                    mRenderer.updateBedViewMatrix(viewMatrix);
                    mModelInit[BED] = false;
                }

                mRenderer.setProjectionMatrix(projMatrix);
                mRenderer.updateViewMatrix(viewMatrix);
            }
        });
        mSurfaceView.setPreserveEGLContextOnPause(true);
        mSurfaceView.setEGLContextClientVersion(2);
        mSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        mSurfaceView.setRenderer(mRenderer);


        //save picutre
        btn_capture_locate = (Button)findViewById(R.id.btn_capture_locate);
        btn_capture_locate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isSaveClick = true;
                mRenderer.printOptionEnable = isSaveClick;
                Toast.makeText(getApplicationContext(), "Saved", Toast.LENGTH_SHORT).show();
            }
        });



    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGestureDetector.onTouchEvent(event);
        mScaleDetector.onTouchEvent(event);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                break;
            case MotionEvent.ACTION_UP:
                break;
        }
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();

        mSurfaceView.onPause();
        mSession.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();


        try {
            if (mSession == null) {
                switch (ArCoreApk.getInstance().requestInstall(this, mUserRequestedInstall)) {
                    case INSTALLED:
                        mSession = new Session(this);
                        Log.d(TAG, "ARCore Session created.");
                        break;
                    case INSTALL_REQUESTED:
                        mUserRequestedInstall = false;
                        Log.d(TAG, "ARCore should be installed.");
                        break;
                }
            }
        }
        catch (UnsupportedOperationException e) {
            Log.e(TAG, e.getMessage());
        } catch (UnavailableApkTooOldException e) {
            e.printStackTrace();
        } catch (UnavailableDeviceNotCompatibleException e) {
            e.printStackTrace();
        } catch (UnavailableUserDeclinedInstallationException e) {
            e.printStackTrace();
        } catch (UnavailableArcoreNotInstalledException e) {
            e.printStackTrace();
        } catch (UnavailableSdkTooOldException e) {
            e.printStackTrace();
        }

        mConfig = new Config(mSession);
        if (!mSession.isSupported(mConfig)) {
            Log.d(TAG, "This device is not support ARCore.");
        }
        mSession.configure(mConfig);
        try {
            mSession.resume();
        } catch (CameraNotAvailableException e) {
            e.printStackTrace();
        }

        mSurfaceView.onResume();
        mSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }


    //물체가 생성될 위치를 잡아주는 함수
    public float[] calculateInitialPosition(int width, int height, float[] projMat, float[] viewMat) {
        return getScreenPoint(width / 2, height - 300, width, height, projMat, viewMat);
    }

    public float[] getScreenPoint(float x, float y, float w, float h,
                                  float[] projMat, float[] viewMat) {
        float[] position = new float[3];
        float[] direction = new float[3];

        x = x * 2 / w - 1.0f;
        y = (h - y) * 2 / h - 1.0f;

        float[] viewProjMat = new float[16];
        Matrix.multiplyMM(viewProjMat, 0, projMat, 0, viewMat, 0);

        float[] invertedMat = new float[16];
        Matrix.setIdentityM(invertedMat, 0);
        Matrix.invertM(invertedMat, 0, viewProjMat, 0);

        float[] farScreenPoint = new float[]{x, y, 1.0F, 1.0F};
        float[] nearScreenPoint = new float[]{x, y, -1.0F, 1.0F};
        float[] nearPlanePoint = new float[4];
        float[] farPlanePoint = new float[4];

        Matrix.multiplyMV(nearPlanePoint, 0, invertedMat, 0, nearScreenPoint, 0);
        Matrix.multiplyMV(farPlanePoint, 0, invertedMat, 0, farScreenPoint, 0);

        position[0] = nearPlanePoint[0] / nearPlanePoint[3];
        position[1] = nearPlanePoint[1] / nearPlanePoint[3];
        position[2] = nearPlanePoint[2] / nearPlanePoint[3];

        direction[0] = farPlanePoint[0] / farPlanePoint[3] - position[0];
        direction[1] = farPlanePoint[1] / farPlanePoint[3] - position[1];
        direction[2] = farPlanePoint[2] / farPlanePoint[3] - position[2];

        normalize(direction);

        position[0] += (direction[0] * 0.1f);
        position[1] += (direction[1] * 0.1f);
        position[2] += (direction[2] * 0.1f);

        return position;
    }

    private void normalize(float[] v) {
        double norm = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        v[0] /= norm;
        v[1] /= norm;
        v[2] /= norm;
    }

    private void hideStatusBarAndTitleBar(){
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void onTableButtonClicked(View view) {
        mSelectedModel = TABLE;
        mTextView.setText(getString(R.string.table_selected));
    }

    public void onChairButtonClicked(View view) {
        mSelectedModel = CHAIR;
        mTextView.setText(getString(R.string.chair_selected));
    }

    public void onBedButtonClicked(View view) {
        mSelectedModel = BED;
        mTextView.setText(getString(R.string.bed_selected));
    }
}