{
  description = "WikiTok for Android — dev shell with SDK, emulator, gradle";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "aarch64-darwin";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ "35.0.0" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "arm64-v8a" ];
        includeNDK = false;
      };
      sdk = androidComposition.androidsdk;
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          sdk
          pkgs.jdk17
          pkgs.gradle_8
        ];
        shellHook = ''
          export ANDROID_HOME="${sdk}/libexec/android-sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          export ANDROID_USER_HOME="$PWD/.android"
          export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
          export JAVA_HOME="${pkgs.jdk17.home}"
          mkdir -p "$ANDROID_AVD_HOME"
        '';
      };
    };
}
