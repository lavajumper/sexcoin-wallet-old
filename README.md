Welcome to _Sexcoin Wallet_, a standalone Sexcoin payment app for your Android device!

__There is a pre-alpha release available for brave folk__
__!!DO NOT RISK LARGE AMOUNTS OF COIN!!__

This project contains several sub-projects:

 * __wallet__:
     The Android app itself. This is probably what you're searching for.
 * __market__:
     App description and promo material for the Google Play app store.
 * __integration-android__:
     A tiny library for integrating Sexcoin payments into your own Android app
     (e.g. donations, in-app purchases).
 * __sample-integration-android__:
     A minimal example app to demonstrate integration of Sexcoin payments into
     your Android app.

This project also depends on the lavajumper/bitcoinj-scrypt library. You must pull this library first and do a maven build before building 'bitcoin-wallet'
This project will be refactored sometime in the future.

Once bitcoinj-scrypt is built, you can build this with Maven:

`mvn clean install`
