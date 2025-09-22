# Testing Guide

## Token Minting Test

The token minting functionality can be tested in two ways:

### 1. From the App's Debug Menu
1. Open the app
2. Tap the Settings icon (gear icon in bottom navigation)
3. Select "Test Token Minting"
4. Tap "Run Test"
5. Check the toast messages and logs for results

### 2. Using Instrumented Tests
Run the instrumented tests from Android Studio or command line:

```bash
./gradlew connectedAndroidTest
```

Or run specific test:
```bash
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.unicitylabs.nfcwalletdemo.TokenMintInstrumentedTest
```

## Test Coverage

The test verifies:
- SDK initialization
- Identity generation
- Token minting with the bundled SDK
- Token transfer between two virtual wallets

## Viewing Test Results

- **In-app tests**: Check Logcat for detailed output with tag "TokenMintTest"
- **Instrumented tests**: Results are available in `app/build/reports/androidTests/`

## Notes

- The bundled SDK is loaded from `app/src/main/assets/unicity-sdk.js`
- The SDK wrapper functions are in `app/src/main/assets/unicity-wrapper.js`
- All test options are available in the Settings menu (no need for debug builds)