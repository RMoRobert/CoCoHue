# CoCoHue

CoCoHue: <b>Co</b>mmunity <b>Co</b>llection of <b>Hue</b> Bridge Apps and Drivers for Hubitat

(Hue Bridge Integration App for Hubitat)

**NOTE:** This repository hosts the test/developmenet version of CoCoHue. Most users will want to install the production version
from <a href="https://github.com/HubitatCommunity/CoCoHue">https://github.com/HubitatCommunity/CoCoHue</a> instead.

This is a Hue Bridge integration designed to replace (or supplement) Hubitat's buit-in Hue Bridge
integration.

## For developers
The .groovy app and driver source files are meant to be "preprocessed" before being added to the hub. An
example of relatively naive (but functional for these purposes) preprocessor is included as a `python3`
script in `preprocessor-cocohue.py`. This script:
  - replaces `#include` "directives" in the Groovy source files with the actual library contents, similar
    to what the hub would do when saving the code (but this way, the user does not need to install the libaries,
    and you do not need to fetch or distribute the "full" version from the hub)
  - replaces the specified "constants" in the source with the literal values, both as specified in the CONSTANTS dictionary in
    the script (each constant value is also a dictionary, which will be looked up by target, allowing different
    values for different targets if needed). Note that this is essentially a search-and-replace, i.e., no regard for
    Groovy parsing, so will also find such instances in comments, etc. Unique names are recommended, which is why all examples
    in this code are surrounded by double underscores (which we do not typically use in Groovy code, though they are valid
    names).
  - evaluates `#IF` "directives" in the Groovy source, invented for this script. In general,
    this can be used to include or exclude certain lines/blocks of code from the final product depending on whether
    the value of the target parameter (passed into the script on execution) matches the one specified in the code.
    See examples in the Groovy source. (This is *not* a general-purpose expression evaluator! Only this specific
    format can be used.)

Example of running the script: `python3 preprocessor-cocohue.py`
NOTE: Make sure the output directory, `full/cocohue` relative to the current path, exists before running.
Processed output will be placed in this directory. These are the files that should be distributed, uploaded
to hub, etc.