# Public Suffix List

`public_suffix_list.dat` is the [Public Suffix List](https://publicsuffix.org/), downloaded from
https://publicsuffix.org/list/public_suffix_list.dat. It is licensed under the
[Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/) (see the header of the file); the app ships it
compiled into `src/main/assets/adblock/psl.txt` (`PublicSuffixListCompiler`).

To update it, download a new `public_suffix_list.dat` here and run

```bash
./gradlew :adblock:testDebugUnitTest -PupdatePsl
```

which writes the asset again; without `-PupdatePsl` the tests fail while the asset is older than the list.
