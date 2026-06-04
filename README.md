# OrangIdentifier - Android App

<img align="right" width="128" src="github_assets/app_icon.png" alt="OrangIdentifier App Icon">

**Individual facial recognition for Bornean orangutans on the edge.** 
This is the offline Android companion app to the [OrangIdentifier ML Pipeline](https://github.com/tit0000/OrangIdentifier).

---

## Overview

The OrangIdentifier Android app brings the power of individual facial recognition directly to the field. Using a lightweight pipeline with **YOLO v2** for face detection and **MegaDescriptor-T** for identity embedding, it can identify orangutans in real-time or from saved media entirely offline on an Android device.

No internet connection is required once the application is installed.

> **ML Pipeline** → separate repository for training and creating the gallery JSON: [tit0000/OrangIdentifier](https://github.com/tit0000/OrangIdentifier)

---

## Demo

![OrangIdentifier app demo — real-time individual identification](github_assets/demo.gif)

> Identifying individuals from live camera feed and saved gallery images.

---

## Features

- **Offline Inference**: Fully functional without an internet connection using on-device TensorFlow Lite models.
- **Media Processing**: Analyze photos and videos from your gallery to identify individuals.
- **Gallery Management**: Manage the identity gallery directly within the app, review known individuals.
- **Scan History**: Keep track of previous identifications with an embedded local database.
- **Add New Individuals**: Register new orangutans into the system directly from the app.

## Screenshots

| Home & Camera | Identification Result | Identity Gallery |
|:---:|:---:|:---:|
| ![Home Screen](github_assets/home_screen.png) | ![Result Screen](github_assets/result_screen.png) | ![Gallery Screen](github_assets/gallery_screen.png) |

---

## Inference pipeline

The app executes a scaled-down version of the Python pipeline using TensorFlow Lite:

```mermaid
flowchart LR
    A[Camera / Photo] --> B[YOLO v2 TFLite\nFace detection]
    B --> C[Crop Face]
    C --> D[MegaDescriptor-T TFLite\nSwin Transformer\n768-dim embedding]
    D --> E{Cosine similarity\nvs gallery.json}
    E -->|sim >= threshold| F[Known individual]
    E -->|sim < threshold| G[Unknown individual]

    style A fill:#1e1e2e,stroke:#585b70,color:#cdd6f4
    style B fill:#1e1e2e,stroke:#585b70,color:#cdd6f4
    style C fill:#1e1e2e,stroke:#585b70,color:#cdd6f4
    style D fill:#1e1e2e,stroke:#585b70,color:#cdd6f4
    style E fill:#1e1e2e,stroke:#fab387,color:#cdd6f4
    style F fill:#1e1e2e,stroke:#a6e3a1,color:#a6e3a1
    style G fill:#1e1e2e,stroke:#f38ba8,color:#f38ba8
```

---

## Models Included

This application requires specific ML models in the `app/src/main/assets/` directory.

- `yolo_v2_detector.tflite` (**Included** - 22MB): Used for fast face detection.
- `megadesc_T_arcface_backbone.tflite` (**Not included** - 112MB): The heavy embedding model. **Must be downloaded separately.**

### Downloading the Backbone Model
Due to GitHub file size limits, the `megadesc_T_arcface_backbone.tflite` model is not included in the repository.

1. Download the model from the HuggingFace repo: [tit0000/OrangIdentifier](https://huggingface.co/tit0000/OrangIdentifier) (or from the Releases page).
2. Place it exactly at: `app/src/main/assets/megadesc_T_arcface_backbone.tflite` before building the app.

---

## Architecture

The app is built using modern Android development practices and Jetpack components:

- **Language**: Kotlin
- **Architecture**: Clean Architecture + MVVM (Model-View-ViewModel)
- **Dependency Injection**: Hilt
- **Database**: Room
- **Camera**: CameraX
- **Machine Learning**: TensorFlow Lite Android Support
- **Asynchronous Programming**: Coroutines & Flow

---

## Building the App

1. Clone this repository:
   ```bash
   git clone https://github.com/tit0000/OrangIdentifier-Android.git
   ```
2. Download the `megadesc_T_arcface_backbone.tflite` model and place it in `app/src/main/assets/`.
3. Open the project in **Android Studio** (Koala or newer recommended).
4. Sync Gradle files.
5. Build and run on your device or emulator.

---

## How to use custom galleries

The app ships with a sample `gallery.json` in the `assets/` folder. 
To use your own gallery:
1. Generate it using the [Python pipeline](https://github.com/tit0000/OrangIdentifier).
2. Replace `app/src/main/assets/gallery.json` before building, OR load it dynamically from the app settings.
