plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.yunho.talkhelper"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.yunho.talkhelper"
        minSdk = 26
        targetSdk = 36  // 구글 플레이: 2026-08-31 부터 새 앱은 API 36 필수
        // GitHub Actions 가 정한 버전 번호(실행 번호 + 100)를 써서, 앱이 새 버전을 알아볼 수 있게 함
        val run = System.getenv("APP_VERSION")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"
        // 중계 서버 (키 없이 설치만 하면 쓰게). Claude 키는 서버의 Cloudflare 비밀에만 있음. 비우면 예전처럼 폰의 키만 씀
        // Play Integrity 용 Google Cloud 프로젝트 번호 (Play Console 에 연결한 프로젝트). 0 이면 쓰지 않음
        buildConfigField("long", "CLOUD_PROJECT_NUMBER", "0L")
        buildConfigField("String", "RELAY_URL", "\"https://malro-dapjang-relay.mernial.workers.dev\"")
    }

    // 업데이트가 기존 앱 위에 덮어 설치되려면 항상 같은 키로 서명해야 함.
    // 키는 공개 저장소에 두지 않고, GitHub 비밀(SIGNING_KEY_B64)에서 빌드할 때만 꺼내 씀.
    // 플레이 스토어용 AAB 도 같은 키를 '업로드 키'로 씀 (실제 앱 서명은 구글이 관리)
    val storePath = System.getenv("SIGNING_STORE_FILE")
    if (storePath != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(storePath)
                storePassword = "talkhelper"
                keyAlias = "talkhelper"
                keyPassword = "talkhelper"
            }
            create("upload") {
                storeFile = file(storePath)
                storePassword = "talkhelper"
                keyAlias = "talkhelper"
                keyPassword = "talkhelper"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // github: 직접 설치용 (앱 안 업데이트 있음)
    // play:   플레이 스토어용 (스토어 정책상 스스로 업데이트하는 기능과 설치 권한을 뺌)
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "STORE_SAFE", "false")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            // 스토어 정책·카카오 우려: 확인 없는 '바로 보내기'와 카톡 메뉴 자동 조작(대화 내보내기·위로 넘기며 모으기)을 끔
            buildConfigField("boolean", "STORE_SAFE", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (storePath != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    compileOptions {
        // Claude SDK 가 쓰는 java.time 등 최신 Java API 를 오래된 안드로이드에서도 쓰도록
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.2.0")
    // 중계 서버에 '플레이에서 받은 진짜 앱'임을 알리는 구글 Play Integrity
    implementation("com.google.android.play:integrity:1.4.0")
    // 플레이 권고: 간접으로 들어오던 옛 androidx.fragment 1.0.0 대신 최신으로
    implementation("androidx.fragment:fragment:1.8.5")
    // 플레이 스토어판: 새 버전이 있는지 플레이 스토어에 물어봄
    "playImplementation"("com.google.android.play:app-update:2.1.0")
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
