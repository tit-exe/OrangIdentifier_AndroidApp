# OrangIdentifier - Android App

<img align="right" width="128" src="github_assets/app_icon.png" alt="OrangIdentifier App Icon">

**Individual facial recognition for Bornean orangutans on the edge.** 
This is the offline Android companion app to the [OrangIdentifier ML Pipeline](https://github.com/tit0000/OrangIdentifier).

---

## Overview

Developed natively in **Android Studio**, the OrangIdentifier app empowers field rangers to photograph primates and obtain automatic individual identification in seconds, entirely offline. 

This app runs the **V3 pipeline** under the hood: a YOLO v2 face detector paired with a **MegaDescriptor** backbone. This state-of-the-art architecture allows the app to perform incredibly fast similarity matching using cosine distance against a lightweight JSON gallery of known individuals.

Crucially, **adding a new individual is easy and instantaneous**: the ranger simply takes a few pictures of the new orangutan, the app extracts the facial embeddings, and the new identity is immediately saved to the local gallery without any retraining required.

---

## Demo

![OrangIdentifier app demo — real-time individual identification](github_assets/demo.gif)

> Identifying individuals from live camera feed and saved gallery images.

---

## Features

- **Fully Offline**: 100% local processing using on-device TensorFlow Lite models. No internet connection or cloud API is needed.
- **Easy Identity Onboarding**: Register new orangutans into the system directly from the app in less than a minute.
- **Real-time Identification**: Point the camera to get live predictions with confidence scores and top-3 hypotheses.
- **Media Processing**: Analyze existing photos and videos from your Android gallery.
- **Continuous Learning**: Rangers can manually correct wrong predictions in real-time, which updates and improves the individual's prototype vector on the fly.
- **Scan History**: Keep track of all past identifications with a robust local SQLite database.

## Screenshots

| Home & Camera | Identification Result | Identity Gallery |
|:---:|:---:|:---:|
| ![Home Screen](github_assets/home_screen.png) | ![Result Screen](github_assets/result_screen.png) | ![Gallery Screen](github_assets/gallery_screen.png) |

---

## Technical Architecture

The app is built using modern Android development practices and Jetpack components:

- **Language**: Kotlin
- **Architecture**: MVVM (Model-View-ViewModel) + Clean Architecture
- **Dependency Injection**: Hilt
- **Local Database**: Room
- **Machine Learning**: TensorFlow Lite Android Support
- **Camera**: CameraX
- **Navigation**: Jetpack Navigation Component
- **Concurrency**: Kotlin Coroutines & Flow (all heavy ML inference runs on `Dispatchers.IO`)

---

## Inference Pipeline

The app executes a highly optimized version of the Python pipeline using TensorFlow Lite:

```mermaid
flowchart LR
    A[Camera / Photo] --> B[YOLO v2 TFLite\nFace detection]
    B --> C[Crop Face 224x224]
    C --> D[MegaDescriptor-T TFLite\nSwin Transformer\n768-dim embedding]
    D --> E{Cosine similarity\nvs gallery.json}
    E -->|sim >= threshold| F[Known individual\n(Confidence %)]
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

This application requires specific ML models to be present in the `app/src/main/assets/` directory.

- `yolo_v2_detector.tflite` (**Included** - 22MB): Used for fast face detection.
- `megadesc_T_arcface_backbone.tflite` (**Not included** - 112MB): The heavy embedding model. **Must be downloaded separately.**

### Downloading the Backbone Model
Due to GitHub file size limits, the 112MB `megadesc_T_arcface_backbone.tflite` model is excluded from the repository.

1. Download the model from the HuggingFace repo: [tit0000/OrangIdentifier](https://huggingface.co/tit0000/OrangIdentifier) (or from the latest GitHub Release).
2. Place it exactly at: `app/src/main/assets/megadesc_T_arcface_backbone.tflite` before building the app.

---

## Building the App

1. Clone this repository:
   ```bash
   git clone https://github.com/tit0000/OrangIdentifier-Android.git
   ```
2. Download the `megadesc_T_arcface_backbone.tflite` model and place it in `app/src/main/assets/`.
3. Open the project in **Android Studio** (Koala or newer recommended).
4. Sync the Gradle files.
5. Hit **Run** to build and install the app on your physical device or emulator.

---

## How to use custom galleries

The app ships with a sample `gallery.json` in the `assets/` folder. This JSON file acts as the "database" of known individuals, storing their L2-normalized embedding vectors (prototypes).

To use your own gallery:
1. Generate a gallery using the [Python pipeline](https://github.com/tit0000/OrangIdentifier).
2. Replace `app/src/main/assets/gallery.json` before building, OR load the JSON file dynamically from the app's settings menu at runtime.
