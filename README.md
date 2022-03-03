CVE-2021-0928, `writeToParcel`/`createFromParcel` serialization mismatch in `android.hardware.camera2.params.OutputConfiguration`

This is exploit using that vulnerability for privilege escalation from installed Android app into Android Settings app (or any other app installed app could send to `<receiver>` declared in `AndroidManifest.xml`, privilege escalation by sending to `<activity>` was possible too, although not presented here)

I've found issue originally on Android 12 Developer Preview 3

Exploit version present in this repo works on Android 12 Beta 2 and 3

Vulnerability was fixed in first official Android 12 release

Writeup below was originally written for Google for consideration of this report as complete exploit chain

At time of writing Android 12 was not available in AOSP (Android Developer Preview/Beta releases are not open source)

![Screenshot of Android notification from Settings app: Hello from uid=1000(system) gid=1000(system) groups=1000(system),1007(log),1065(reserved_disk),1077(external_storage),3001(net_bt_admin),3002(net_bt),3003(inet),3007(net_bw_acct),9997(everybody) context=u:r:system_app:s0](screenshot.png)

# Introduction to Parcel

Most of IPC on Android is done through class called [`Parcel`](https://developer.android.com/reference/android/os/Parcel)

Basic usage of Parcel is as following:

```java
Parcel p = Parcel.obtain();
p.writeInt(1);
p.writeString("Hello");
```

Then `Parcel` is sent to another process [through `Binder`](https://developer.android.com/reference/android/os/IBinder#transact(int,%20android.os.Parcel,%20android.os.Parcel,%20int)). Alternatively for testing one can call [`p.setDataPosition(0)`](https://developer.android.com/reference/android/os/Parcel#setDataPosition(int)) to rewind parcel to beginning position and start reading:

```java
int a = p.readInt(); // a = 1
String b = p.readString(); // b = "Hello"
```

It should be noted that parcel internally holds position from which reads are performed. It it responsibility of `Parcel` class user to ensure that `read*` methods match previously used `write*` methods, otherwise subsequent reads will be from wrong positions in buffer

Parcel also provides ability to write custom objects, preferred way to do so is by implementing [`Parcelable` interface](https://developer.android.com/reference/android/os/Parcelable)

Here is example implementation of `Parcelable` interface (irrelevant code removed, [`WindowContainerTransaction` class](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/window/WindowContainerTransaction.java) is used in exploit as part of gadget chain, however there isn't anything wrong with it)

```java
package android.window;
public final class WindowContainerTransaction implements Parcelable {
    private final ArrayMap<IBinder, Change> mChanges = new ArrayMap<>();
    private final ArrayList<HierarchyOp> mHierarchyOps = new ArrayList<>();

    private WindowContainerTransaction(Parcel in) {
        in.readMap(mChanges, null /* loader */);
        in.readList(mHierarchyOps, null /* loader */);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeMap(mChanges);
        dest.writeList(mHierarchyOps);
    }

    @NonNull
    public static final Creator<WindowContainerTransaction> CREATOR =
            new Creator<WindowContainerTransaction>() {
                @Override
                public WindowContainerTransaction createFromParcel(Parcel in) {
                    return new WindowContainerTransaction(in);
                }
            };
}
```

As can be seen above, `writeToParcel()` method is used during writing. Then while reading `CREATOR.createFromParcel()` factory method is called. It is responsibility of `Parcelable` implementation to ensure that `createFromParcel` reads same amount of data as was written by `writeToParcel`, otherwise all subsequent reads from that `Parcel` will read data from wrong offset

Such class can be written to/read from `Parcel` through:

* Directly calling `obj.writeToParcel(parcel, 0)` / `obj = WindowContainerTransaction.CREATOR.createFromParcel()`, this is often used when type of class is known, for example when `Parcelable` has field with different `Parcelable` or in code generated by AIDL when defined RPC method has `Parcelable` as argument
* Through `Parcel.writeParcelable`/`readParcelable`. [`writeParcelable`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=1909;drc=58787794eb5c879f6e39ee58c0071b36e337f8e3) first writes name of class and then calls `writeToParcel` method from `Parcelable` interface. [`readParcelable`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=3282;drc=58787794eb5c879f6e39ee58c0071b36e337f8e3) reads written class name, finds class with that name in provided `ClassLoader` or `BOOTCLASSPATH` if null was provided. Once class is found it's static field `CREATOR` is used to obtain [`Parcelable.Creator`](https://developer.android.com/reference/android/os/Parcelable.Creator) instance which is factory used to read that class. It is important to note that when `readParcelable` method is used it can read any `Parcelable` available in class path as name of object to be created is read from same `Parcel`
* `readParcelable` is used by many other `Parcel` methods, for example `readList` seen in example above reads elements through `readValue`, which is most generic method of transferring objects in `Parcel` and one of ways it uses is through `readParcelable`. Also in above example due to Java's Type Erasure `ArrayList<HierarchyOp> mHierarchyOps` field can actually contain any objects supported by Parcel, not only those compatible with type specified in generic type declaration

# `writeToParcel`/`createFromParcel` mismatches

As noted above it is responsibility of `Parcelable` interface implementation to ensure that `createFromParcel` reads same amount of data from `Parcel` as matching `writeToParcel` has previously written. Whenever there is in `BOOTCLASSPATH` a `Parcelable` which can violate that contract it creates a vulnerability as it allows for following scenario:

1. An evil application sends to `system_server` a `Bundle` OR `Parcelable` containing faulty `Parcelable` instance along with specifically constructed data that will be actually read in step 3 but passed verbatim during step 2
2. `system_server` verifies `Bundle` is safe and then forwards it OR `system_server` passes provided `Parcelable` to AIDL method that also has critical data passed in next parameter (if data received in that parameter could be modified that would cause security issue)
3. Another app receives data from `system_server` and trusts it, however due to faulty serialization data that it actually sees differs from data `system_server` intended to send

I've used "OR" in above steps as these steps describe both an [old exploit variant which leads to starting arbitrary Activity which I've published in 2017](https://github.com/michalbednarski/ReparcelBug) (on the left side of "OR") and a new variant which I'll describe here in next section

# How `BroadcastReceiver` is executed in app

From the point of application developer using APIs available in Android SDK the way [`BroadcastReceiver`](https://developer.android.com/guide/components/broadcasts) works is that one application calls [`sendBroadcast`](https://developer.android.com/reference/android/content/Context#sendBroadcast(android.content.Intent)) (although often apps want to receive Broadcasts from system, not app) and then broadcasted Intent is matched to `<receiver>` defined in `AndroidManifest.xml`, when that happens system starts process of receiving application, instantiates `BroadcastReceiver` subclass as defined in `<receiver android:name>` attribute and then calls [`onReceive`](https://developer.android.com/reference/android/content/BroadcastReceiver#onReceive(android.content.Context,%20android.content.Intent)) method

Let's take a look at communication with `system_server` happening in process receiving broadcast:

* When application process is initially started it [calls `IActivityManager.attachApplication()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityThread.java;l=7340;drc=f53e23b917aa0f6a6310e46a233a29b6d6226b2c), by doing so it passes [`IApplicationThread`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/IApplicationThread.aidl) handle which is used by system to tell application process what to do
* When system wants to execute manifest-registered `BroadcastReceiver` in application process, it calls [`scheduleReceiver`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityThread.java;l=950;drc=f53e23b917aa0f6a6310e46a233a29b6d6226b2c) method using `IApplicationThread` described in previous point. This method has multiple arguments but here most important ones are first two:
  1. `Intent intent`, which was previously passed to system when `sendBroadcast()` was called
  2. `ActivityInfo info`, which contains information about component that has to be executed. Value to this parameter is taken by system from Package Manager Service. Most importantly data passed in this parameter includes path to file from which Java class handling received broadcast will be loaded

At this point you probably can guess what this new exploit path is: call `sendBroadcast()` passing an `Intent` that will cause that when system tries to call `scheduleReceiver` it'll cause that application in which `scheduleReceiver` is invoked will see tampered `ActivityInfo`

It should be noted that this new exploit path became viable in Android 12 as previously there was no way to put arbitrary `Parcelable`s in `Intent` ([Intent extras](https://developer.android.com/reference/android/content/Intent#putExtra(java.lang.String,%20android.os.Parcelable)) don't count as they are put into `Bundle` which has its whole length written into Parcel and is [read as single blob](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=1671-1681;drc=5d123b67756dffcfdebdb936ab2de2b29c799321), so extras cannot cause misinterpretation of `Intent` object containing them)

# Triggering `writeToParcel`/`createFromParcel` mismatch

Most of the time `writeToParcel`/`createFromParcel` mismatches are cases where in one of these methods one of the fields is forgotten or written twice, in such case sending such object will always trigger mismatch. (Most of the time that happens when object while `Parcelable`, isn't really used across processes, otherwise that would be quickly noticed during normal usage)

This time however that wasn't the case and triggering mismatch isn't obvious

Let's take a look at vulnerable class ([original was here](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/hardware/camera2/params/OutputConfiguration.java;drc=46c390a1c695e2dc458cb889e40559f259f60aed), lines marked `// New in Android 12` were manually added as they weren't present in AOSP at time of writing) ([Here's commit originally introducing vulnerability](https://android.googlesource.com/platform/frameworks/base/+/a69b1bc58b0838e06deefb190e226774a34671e6%5E%21/#F10), however it was published after Android 12 was released)

```java
package android.hardware.camera2.params;

public final class OutputConfiguration implements Parcelable {
    private OutputConfiguration(@NonNull Parcel source) {
        int rotation = source.readInt();
        int surfaceSetId = source.readInt();
        int surfaceType = source.readInt();
        int width = source.readInt();
        int height = source.readInt();
        boolean isDeferred = source.readInt() == 1;
        boolean isShared = source.readInt() == 1;
        ArrayList<Surface> surfaces = new ArrayList<Surface>();
        source.readTypedList(surfaces, Surface.CREATOR);
        String physicalCameraId = source.readString();
        boolean isMultiResolution = source.readInt() == 1; // New in Android 12
        ArrayList<Integer> sensorPixelModesUsed = new ArrayList<Integer>(); // New in Android 12
        source.readList(sensorPixelModesUsed, Integer.class.getClassLoader()); // New in Android 12

		// SNIP: copy values from variables set above to fields of this class
    }

    public static final @android.annotation.NonNull Parcelable.Creator<OutputConfiguration> CREATOR =
            new Parcelable.Creator<OutputConfiguration>() {
        @Override
        public OutputConfiguration createFromParcel(Parcel source) {
            try {
                OutputConfiguration outputConfiguration = new OutputConfiguration(source);
                return outputConfiguration;
            } catch (Exception e) {
                Log.e(TAG, "Exception creating OutputConfiguration from parcel", e);
                return null;
            }
        }

        @Override
        public OutputConfiguration[] newArray(int size) {
            return new OutputConfiguration[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null");
        }
        dest.writeInt(mRotation);
        dest.writeInt(mSurfaceGroupId);
        dest.writeInt(mSurfaceType);
        dest.writeInt(mConfiguredSize.getWidth());
        dest.writeInt(mConfiguredSize.getHeight());
        dest.writeInt(mIsDeferredConfig ? 1 : 0);
        dest.writeInt(mIsShared ? 1 : 0);
        dest.writeTypedList(mSurfaces);
        dest.writeString(mPhysicalCameraId);
        dest.writeInt(mIsMultiResolution ? 1 : 0); // New in Android 12
        dest.writeList(mSensorPixelModesUsed); // New in Android 12
    }

    private ArrayList<Surface> mSurfaces;
    private final int mRotation;
    private final int mSurfaceGroupId;
    private final int mSurfaceType;
    private final Size mConfiguredSize;
    private final int mConfiguredFormat;
    private final int mConfiguredDataspace;
    private final int mConfiguredGenerationId;
    private final boolean mIsDeferredConfig;
    private boolean mIsShared;
    private String mPhysicalCameraId;
    private boolean mIsMultiResolution; // New in Android 12
    private ArrayList<Integer> mSensorPixelModesUsed; // New in Android 12
}
```

So whats wrong here and why this change introduces vulnerability? As I've said while discussing `WindowContainerTransaction` example `Parcelable`, `readList` can actually fill list with any object supported by `Parcel`, not only ones matching generic declaration (`ArrayList<Integer>`). However since we're just using this class as part of serialization gadget chain and not actually using it that field won't be used for anything else than reading and writing to `Parcel` (and attempts to use elements from `ArrayList` containing elements mismatching its generic declaration would only lead to `ClassCastException` anyway), that isn't problem in itself

In this class there's also a `try-catch` within `createFromParcel`, which means that if during read an `Exception` is thrown, reading of `OutputConfiguration` will be stopped and reading of object containing `OutputConfiguration` will proceed. When that happens whole `OutputConfiguration` will be written to Parcel but it'll be read only to point at which `Exception` happened. This creates mismatch as unconsumed data written within `OutputConfiguration.writeToParcel` will actually be read by object that was calling `OutputConfiguration.CREATOR.createFromParcel`

Now, combination of these two (allowing nesting arbitrary objects supported by `Parcel` and wrapping that within `try-catch` without rethrow) gives ability to construct `Parcelable` that can be written by `system_server` and later be read in a way that is controlled by app that initially constructed `Parcelable` being forwarded by `system_server`

Ok, so in order to construct such `Parcelable` now we need to find something to be put in `mSensorPixelModesUsed` that will be successfully read in `system_server` (as this object is being received from attacker app through `Parcel`), successfully written by `system_server` and then will fail to unparcel and throw an `Exception` in victim app

One of ways to do so is to use class that is present within `system_server` but not in apps, so that attempting to deserialize it would lead to `ClassNotFoundException`. I cannot however pick a `Parcelable` from `system_server` as `readParcelable` without explicitly specified `ClassLoader` will only search `BOOTCLASSPATH` which won't contain `system_server` specific classes. Solution to that problem is to use one of `Serializable` classes as [`ObjectInputStream` will pick `ClassLoader` from first non-`BOOTCLASSPATH` method in stack trace](https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/io/ObjectInputStream.java;l=682;drc=6bd07db6e4f0806b658fc44bfee5dbef2c409540)

I've picked [`PackageManagerException`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/pm/PackageManagerException.java), however before we use it there's one more thing we need to do. In `OutputConfiguration` constructor when `readList` is called `loader` argument is explicitly set to `Integer.class.getClassLoader()`. That [`loader` value is propagated to `readValue()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=3641;drc=58787794eb5c879f6e39ee58c0071b36e337f8e3), then [to `readSerializable()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=3236;drc=58787794eb5c879f6e39ee58c0071b36e337f8e3) and within [`readSerializable()` if loader parameter isn't null it is used instead of `resolveClass` from `ObjectInputStream` (that `c != null` check does nothing because when `Class.forName` doesn't find class it'll throw exception instead of returning null)](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=3457-3462;drc=58787794eb5c879f6e39ee58c0071b36e337f8e3). The way around is quite simple though, we just need to wrap `PackageManagerException` in some `Parcelable` that does `readList` without specifying `ClassLoader`. This is where described above `WindowContainerTransaction` class comes in

So, at this point we have following object:

* `OutputConfiguration`
  * `mSensorPixelModesUsed.get(0) = WindowContainerTransaction`
    * `mHierarchyOps.get(0) = PackageManagerException` 

Now such object can successfully deserialized within `system_server`: when `WindowContainerTransaction` calls `readList` it'll try finding `PackageManagerException` class using system servers `ClassLoader` (not `BootClassLoader`), as it can find it in stack trace. That class loader happens to be present within stack trace because while all of following methods weren't from system server class path: `Binder#execTransact()`, `IActivityManager$Stub#onTransact()` generated by AIDL and methods from all used `Parcelable` classes, there was method declared within system server in stack trace: [an overridden `onTransact` within `ActivityManagerService`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=2841;drc=6dcc612d7a12dd3e0ba8af933d32d54767e8097b). Therefore `system_server` can read and later write such object to `Parcel` and when target app attempts reading it `PackageManagerException` class won't be available and therefore `ClassNotFoundException` will be thrown, [wrapped into `RuntimeException`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=3472;drc=58787794eb5c879f6e39ee58c0071b36e337f8e3) and then [caught by `OutputConfiguration` `CREATOR`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/hardware/camera2/params/OutputConfiguration.java;l=637-640;drc=46c390a1c695e2dc458cb889e40559f259f60aed)

So we triggered this mismatch. Well, not really yet at this point because exception was caught when no unread data was left by `OutputConfiguration.writeToParcel`, but we can easily add another item to `mSensorPixelModesUsed` `List` and that item will be written through `Parcel.writeValue` and left unread after reading `OutputConfiguration`

# Putting that in `Intent`

As noted above I'll want to trigger mismatch from `Intent` object, as it will be passed by `system_server` to an AIDL method which has `Intent` in first parameter and execution information in second parameter, so that serialization/deserialization of `Intent` passed in first parameter lead to modification of value in second parameter

In [`Intent.readFromParcel()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/Intent.java;l=10862;drc=master) all values are read through dedicated typed methods so there we cannot specify custom `Parcelable` class

Within `Intent` though, there's nested `ClipData` and since Android 12 in `ClipData$Item` there's a new field `ActivityInfo mActivityInfo` (was not present in AOSP at time of initial writing, [here's commit introducing that field](https://android.googlesource.com/platform/frameworks/base/+/c932060175133e266233f1c7667dc69470fbc62e%5E%21/#F2), this field is read through `in.readTypedObject(ActivityInfo.CREATOR)` inside [`ClipData(Parcel in)` constructor](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/ClipData.java;l=1147;drc=921fc028f3687af8d1ab41cce58dba32e8b9082e))

Then within [`ActivityInfo(Parcel source)` constructor](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/ActivityInfo.java;l=1338;drc=5d123b67756dffcfdebdb936ab2de2b29c799321) again there isn't way to put custom `Parcelable`, but as `ActivityInfo` extends from `ComponentInfo` it has `applicationInfo` field

Finally within `ApplicationInfo` there's `SparseArray<int[]> splitDependencies` field, which is [read through `readSparseArray`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/ApplicationInfo.java;l=1907;drc=6dd6c5a6032610736a5457107fae855e22d5f6f6), which in turn [uses `readValue` to read `SparseArray` items](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=3661;drc=58787794eb5c879f6e39ee58c0071b36e337f8e3)

At this point we could place `OutputConfiguration` within `splitDependencies`, however reading `splitDependencies` is followed by few `readString8()` calls and it'd be nice to have full control over unconsumed data after mismatch happens so we can directly place empty strings there and not worry about different interpretation of unconsumed data

To do so, first we need to put some raw data container within `OutputConfiguration.mSensorPixelModesUsed` that will be written through `writeValue`. I've chosen `Bundle`. That way in unconsumed data we'll have left:

1. [`writeValue` `VAL_BUNDLE` tag](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=1811;drc=58787794eb5c879f6e39ee58c0071b36e337f8e3)
2. [Length of raw data (this link also applies to remaining items in this list)](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=1601-1603;drc=5d123b67756dffcfdebdb936ab2de2b29c799321)
3. `BUNDLE_MAGIC`
4. Raw data passed verbatim through `Parcel.appendFrom`

So we have three `Parcel.writeInt` items we'd have unconsumed, we can get rid of them by wrapping `OutputConfiguration` within some `Parcelable` that while reading it reads arbitrary `Parcelable` value followed by three ints. I've found that in [`ZenPolicy CREATOR`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/service/notification/ZenPolicy.java;l=807-810;drc=931656906265199ef1facfaf927cb3c7cff175c4)

To sum up we've got following object hierarchy (that is present in `system_server` and which it attempts passing to `scheduleReceiver`)

* `Intent`
  * `mClipData = ClipData`
    * `mItems.get(0).mActivityInfo = ActivityInfo`
	  * `applicationInfo = ApplicationInfo`
	    * `splitDependencies.get(0) = ZenPolicy`
		  * `mVisualEffects.get(0) = OutputConfiguration`
		    * `mSensorPixelModesUsed.get(0) = WindowContainerTransaction`
			  * `mHierarchyOps.get(0) = PackageManagerException`
		    * `mSensorPixelModesUsed.get(1) = Bundle`

That is written by `system_server`. Then receiving application reads everything up to (and including `readSerializable` data of) `PackageManagerException` normally, however after `Serializable` data for `PackageManagerException` are read an exception is thrown and reading of everything below `OutputConfiguration` is cancelled, leaving `Bundle` unread. Reading proceeds to `ZenPolicy` which consumes three ints that precede raw data within `Bundle`. Then `ApplicationInfo` reading proceeds with reading data that were previously raw data passed verbatim in Bundle. Reading of that raw data will continue with remaining objects in this stack (`ApplicationInfo`, `ActivityInfo`, `ClipData` and `Intent`) and then that raw data will be used for reading next `handleReceiver` method parameter

# What then happens within `handleReceiver`

As I've just said below now remaining `scheduleReceiver` parameters are read from buffer controlled by attacker.

Let's take a look at what happens once that method is invoked.

First [`scheduleReceiver`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityThread.java;l=950;drc=f53e23b917aa0f6a6310e46a233a29b6d6226b2c) packs values from all arguments and uses `sendMessage()` to pass execution to main thread

Next, on main thread [`handleReceiver`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityThread.java;drc=f53e23b917aa0f6a6310e46a233a29b6d6226b2c;l=3979) is called

`handleReceiver` calls `getPackageInfoNoCheck`, passing it `ApplicationInfo` which it received as part of `ActivityInfo` which was passed to `scheduleReceiver` argument

`getPackageInfo` checks if package with given name is already [present in cache](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityThread.java;drc=f53e23b917aa0f6a6310e46a233a29b6d6226b2c;l=2343) and if not it [constructs new `LoadedApk` instance](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityThread.java;drc=f53e23b917aa0f6a6310e46a233a29b6d6226b2c;l=2369), passing it `ApplicationInfo` object received earlier (Since attacker wants to cause new `LoadedApk` to be constructed a `packageName` of package that wasn't earlier seen in this process is used)

Then [`ContextImpl.getClassLoader()` method](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ContextImpl.java;l=400;drc=4ee7e55df04d9b6fca20db6ff78ff69ac06eadf6) is used, which at first run delegates to [`mPackageInfo.getClassLoader()`, with `mPackageInfo` being a `LoadedApk`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/LoadedApk.java;l=978;drc=3e308c62708443e24ba44ecac683a7fe4d9a7ac2) constructed in previous paragraph

Then there's [`createOrUpdateClassLoaderLocked`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/LoadedApk.java;l=721;drc=3e308c62708443e24ba44ecac683a7fe4d9a7ac2), which calls [`makePaths` to populate `zipPaths`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/LoadedApk.java;l=802;drc=3e308c62708443e24ba44ecac683a7fe4d9a7ac2) with paths to be used in `ClassLoader`, then they are [joined and assigned to `zip` variable](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/LoadedApk.java;l=881;drc=3e308c62708443e24ba44ecac683a7fe4d9a7ac2) and that is passed to `createClassLoader`

`makePaths` fills `zipPaths` using information from `ApplicationInfo`, most importantly this includes [`sourceDir`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/LoadedApk.java;l=429;drc=3e308c62708443e24ba44ecac683a7fe4d9a7ac2). Attacker application makes injected `ApplicationInfo` with `sourceDir` set to path to own apk, therefore receiver class will be actually loaded from attacker apk. This directly leads to execution of attacker-controlled code within application receiving broadcast

# Note on hidden API checks

There was one more thing that needed to be bypassed: [hidden API checks](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces). These were never meant to be security boundary (as application can always use NDK and call underlying syscall directly), but in this case they were bypassed by constructing crafted `ClipData` by manually writing data to `Parcel` and then using `readParcelable`. Such `ClipData` could be then normally attached to `Intent` and then passed to `sendBroadcast()` so sending broadcast itself was done using only public APIs

# Fixes

The above writeup was originally sent to Google and looks like they've made use of it as there are multiple fixes resulting from it (I think, I have no proof about direct causality)

Released with Android 12:

* [Exception swallowing from `OutputConfiguration` and related classes was removed](https://android.googlesource.com/platform/frameworks/base/+/6b0bcd60c81003e6a193aeccf44ee03f188e3984%5E%21/)
* [`OutputConfiguration#mSensorPixelModesUsed` is no longer written through `writeValue`](https://android.googlesource.com/platform/frameworks/base/+/ebbb0ce80098f34905cc483a2022a616c30427e1%5E%21/#F0)
* [`ClipData#mActivityInfo` is no longer written to `Parcel` unless explicitly requested during write](https://android.googlesource.com/platform/frameworks/base/+/d07dca11abb496b2ad8edff70327c277ecfa05bd%5E%21/) (so `Intent` can no longer contain arbitrary `Parcelable`s from `BOOTCLASSPATH`, eliminating this exploitation technique)

Present only on `master` branch at time of writing, not in released versions, probably will appear in Android 13 (not in 12L):

* [There are new `List` reading methods on Parcel that check type of items](https://android.googlesource.com/platform/frameworks/base/+/8a86e7d51e7ee31557f3c33eec8f11f032c8e25b%5E%21/) and [untyped versions have been marked as deprecated](https://android.googlesource.com/platform/frameworks/base/+/aa4e9dfda8174d604244a0aed408040d1adbf62c%5E%21/)
* [There's new method `Parcel#enforceNoDataAvail()` that checks that there are no unread data left in Parcel,](https://android.googlesource.com/platform/frameworks/base/+/d117f6f6d50849740bc3b5baac1beb949047f589%5E%21/) that apparently will be used by AIDL after reading RPC call arguments. Usually my exploits usually relied on fact that after all data was read from Parcel everything else was ignored, that would no longer will be the case, although I think in many cases one could construct data that will cause at end seek to final position so this one isn't strong mitigation. Anyway sometimes such issues occur naturally undetected so that would catch those. [Further discussion about that is in issue #3](https://github.com/michalbednarski/ReparcelBug2/issues/3)
* [Every item in `Bundle` will have its length saved separately.](https://android.googlesource.com/platform/frameworks/base/+/9ca6a5e21a1987fd3800a899c1384b22d23b6dee%5E%21/) This pretty much kills whole bug class from which I've been reporting bugs privately to Google since 2014, [published description in 2017](https://github.com/michalbednarski/IntentsLab/issues/2#issuecomment-344365482) and [code itself about year later](https://github.com/michalbednarski/ReparcelBug). If I'm counting correctly that will be 8 years of bug class life (I honestly have no idea if that is a lot, although there still might be non-`Bundle` exploit variants, like exactly this one (although exactly this one was already fixed))
