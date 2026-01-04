# ARCore 16-bit Raw Depth Capture Utility

This Flutter application is a specialized utility designed to capture high-fidelity environmental data using the [**ARCore Raw Depth API**](https://developers.google.com/ar/develop/java/depth/raw-depth). It exports multi-layer TIFF files containing aligned RGB images and 16-bit depth maps.

## 🚀 Key Features
* **16-bit Raw Depth Acquisition:** Leverages `acquireRawDepthImage16Bits()` to preserve millimeter-level precision.
* **Multi-Layer TIFF Export:** Saves data into a single container:
    * **Layer 0:** 8-bit RGB Image for visualization and segmentation.
    * **Layer 1:** 16-bit Depth Map (packed into RG channels) for mathematical distance calculations.
* **Metadata Integration:** Automatically embeds Camera Intrinsics ($fx, fy, cx, cy$) into the TIFF `imageDescription` tag.
* **User-Defined Storage:** Uses `FilePicker` to allow users to select specific save locations rather than defaulting to the standard gallery.

## 🛠 Architecture
The app uses a hybrid architecture to bridge low-level ARCore buffers with Flutter’s UI:

1. Native Kotlin (`DepthARView.kt`):
    * Manages the ARCore Session with `Config.DepthMode.AUTOMATIC`.
    * Converts YUV camera frames to RGB Bitmaps using OpenCV.
    * Encodes multi-page TIFFs using the `Android-TiffBitmapFactory` (deckerst fork).
2. Flutter Frontend (`ar_screen.dart`):
    * Displays the real-time AR feed via `PlatformView`.
    * Triggers capture via `MethodChannel` and handles file system persistence.

## 📥 Installation
1. **Clone the Repository:**
   ```bash
   git clone https://github.com/IoT-gamer/arcore_depth_capture_utility.git
   cd arcore_depth_capture_utility
   ```
2. **Run**:
    ```bash
    flutter run
    ```

##  📝 Notes
- The resolution of the ARCore Raw Depth API depth map is typically 160x90 pixels, but can be higher, up to 640x480 pixels, on some devices. The exact resolution depends on the specific device and its hardware capabilities, such as the presence of a Time-of-Flight (ToF) sensor.
    - May meed to upscale depth map for alignment with RGB image.
- Devices without a ToF sensor may produce less accurate depth data.
- Moving the camera significantly improves raw depth accuracy and quality in ARCore, especially on devices that do not have a dedicated hardware depth sensor.
    - Move the phone in a slow, smooth arc (about 10–20 cm) around the object you are about to capture.
- Stay within the optimal range for the Raw Depth API, typically between **0.5 meters and 5 meters**.
- **TODO:** Test with Segment Anything (SAM) model for object isolation using the RGB layer for object size estimation viability.
- It is normal for some areas of the depth map to have invalid or missing depth values, especially in regions where the camera cannot accurately measure depth (e.g., reflective surfaces, transparent objects, or areas with insufficient texture). These invalid depth values are typically represented as zeros or very high values in the depth map.

## 📐 Mathematical Reconstruction
The depth values in the 16-bit depth map represent the distance from the camera to the surfaces in the environment. To reconstruct 3D points $(X, Y, Z)$ for any pixel $(u, v)$ from the depth map, you can use the following equations based on the camera intrinsics metadata ($fx, fy, cx, cy$) saved in the TIFF:
1. Depth ($Z$) in millimeters:
$$Z_{(mm)} = (Red \times 256) + Green$$
2. Coordinates in centimeters:
$$Z_{cm} = Z_{mm} / 10.0$$
$$X_{cm} = (u - cx) \times Z_{cm} / fx$$
$$Y_{cm} = (v - cy) \times Z_{cm} / fy$$


## 📄 License
This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgements
- [ARCore](https://developers.google.com/ar) - For providing the Raw Depth API.
- [Android-TiffBitmapFactory deckerst Fork](https://github.com/deckerst/Android-TiffBitmapFactory) - For TIFF saving support.
