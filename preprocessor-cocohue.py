import ast
import os.path
import sys

COCOHUE = "CoCoHue - Hue Bridge Integration"

# -----------------------------
# Will be changed by main() according to command line input, or default to:
#TARGET = COCOHUE
TARGET: str

ALL_TARGETS = [COCOHUE]
ALL_TARGET_NAMES = {"COCOHUE": COCOHUE}

# -----------------------------

driverFiles = [
   "cocohue-bridge-driver.groovy",
   "cocohue-button-driver.groovy",
   "cocohue-ct-bulb-driver.groovy",
   "cocohue-dimmable-bulb-driver.groovy",
   "cocohue-group-driver.groovy",
   "cocohue-motion-sensor-driver.groovy",
   "cocohue-plug-driver.groovy",
   "cocohue-rgb-bulb-driver.groovy",
   "cocohue-rgbw-bulb-driver.groovy",
   "cocohue-scene-driver.groovy",
]

appFiles = [
   "cocohue-app.groovy"
]

libraries = {
   "RMoRobert.CoCoHue_Bri_Lib": "cocohue-library-bri.groovy",
   "RMoRobert.CoCoHue_Common_Lib": "cocohue-library-common.groovy",
   "RMoRobert.CoCoHue_Constants_Lib": "cocohue-library-constants.groovy",
   "RMoRobert.CoCoHue_CT_Lib": "cocohue-library-ct.groovy",
   "RMoRobert.CoCoHue_Effect_Lib": "cocohue-library-effect.groovy",
   "RMoRobert.CoCoHue_Flash_Lib": "cocohue-library-flash.groovy",
   "RMoRobert.CoCoHue_HueSat_Lib": "cocohue-library-huesat.groovy",
}

# These will be searched for in preprocessed files and replaced with the text matching the target key:
# (Note: Groovy source file search is quite naive -- essentially search and replace, so will happen even
# inside comments, etc. "Constant" names have been chosen to be unusual for this purpose, e.g., surrounded
# by double underscores and named uniquely.)
CONSTANTS = {
   "__APP_NAME__": {
      COCOHUE: "CoCoHue - Hue Bridge Integration"
   },
   "__APP_DESCRIPTION__": {
      COCOHUE: "Community-created Philips Hue integration for Hue Bridge lights and other Hue devices and features"
   },
   "__DOCUMENTATION_LINK__": {
      COCOHUE: "https://community.hubitat.com/t/release-cocohue-hue-bridge-integration-including-scenes/27978"
   },
   "__NAMESPACE__": {
       COCOHUE: "RMoRobert"
   },
   "__DNI_PREFIX__": {
      COCOHUE: "CCH"
   },
   "__DRIVER_NAME_BRIDGE__": {
      COCOHUE: "CoCoHue Bridge"
   },
   "__DRIVER_NAME_BUTTON__": {
      COCOHUE: "CoCoHue Button"
   },
   "__DRIVER_NAME_CT_BULB__": {
      COCOHUE: "CoCoHue CT Bulb"
   },
   "__DRIVER_NAME_DIMMABLE_BULB__": {
      COCOHUE: "CoCoHue Dimmable Bulb"
   },
   "__DRIVER_NAME_GROUP__": {
      COCOHUE: "CoCoHue Group"
   },
   "__DRIVER_NAME_MOTION__": {
      COCOHUE: "CoCoHue Motion Sensor"
   },
   "__DRIVER_NAME_PLUG__": {
      COCOHUE: "CoCoHue Plug"
   },
   "__DRIVER_NAME_RGBW_BULB__": {
      COCOHUE: "CoCoHue RGBW Bulb"
   },
   "__DRIVER_NAME_RGB_BULB__": {
        COCOHUE: "CoCoHue RGB Bulb"
   },
   "__DRIVER_NAME_SCENE__": {
      COCOHUE: "CoCoHue Scene"
   }
}

CONSTANT_KEYS = [key for key in CONSTANTS]

#------------------------------
# FILE PROCESSING:
# ------------------------------

DRIVER_DIR = "drivers"
APP_DIR = "apps"
LIB_DIR = "libraries"

totalProcessed = 0

def processFile(sourceFile: str, destFile: str, target: str, preprocessLibrary=True):
   print("  PROCESSING: " + sourceFile + " -> " + destFile)
   if (os.path.isfile(destFile)):
      os.remove(destFile)
   newFile = open(destFile, "a+")
   libNamesToInclude = []

   with open(sourceFile, "r") as ogFile:
      # Regular lines of code (save part of library processing for later)
      inIf = False
      skipLines = False
      for line in ogFile:
         # Handle #include "statements" (part 1):
         if line.strip().startswith("#include") and not skipLines:
            libName =  line.split("#include")[-1].strip()
            libNamesToInclude.append(libName)
         elif line.strip().startswith("#IF "):
               inIf = True
               skipLines = not checkConditionFromLine(line, target)
         elif line.strip().startswith("#ENDIF"):
            inIf = False
            skipLines = False
         # Handle "constant" replacements:
         elif not (inIf and skipLines):
            newFile.write(processConstants(line, target))
      # Add libraries/includes at the end of file (this is part 2):
      for libName in libNamesToInclude:
         libFilePath = os.path.join(LIB_DIR, libraries[libName])
         libFile = open(libFilePath, "r")
         toPrepend = "\n\n// ~~~ IMPORTED FROM " + libName + " ~~~\n"
         newFile.write(toPrepend)
         # Just write line if not configured to preprocess library:
         if (preprocessLibrary == False):
            newFile.write(libFile.read())
            libFile.close()
         # Otherwise, do similar preprocessing as above (but no check for #include, not allowed here):
         else:
            for line in libFile:
               if  line.strip().startswith("#IF "):
                  inIf = True
                  skipLines = not checkConditionFromLine(line, target)
               elif line.strip().startswith("#ENDIF"):
                  inIf = False
                  skipLines = False
               elif not (inIf and skipLines):
                  newFile.write(processConstants(line, target))
         libFile.close()
   newFile.close()

def checkConditionFromLine(line: str, target: str) -> bool:
   condition = line.replace("#IF ", "").strip()
   (lhs, rhs) = condition.split("==")
   return CONSTANTS[lhs.strip()][target] == ast.parse(rhs.strip())

# Naive replacement of any constants in this line with value; returns new line after processing,
# or original line if no replacements were made
def processConstants(srcLine: str, target: str):
   if any(c in srcLine for c in CONSTANT_KEYS):
      newLine = srcLine
      for const in CONSTANT_KEYS:
         newVal = CONSTANTS[const][target]
         newLine = newLine.replace(const, newVal)
      return newLine
   else:
      return srcLine

#------------------------------
# BROAD APP/DRIVER PROCESSING:
# ------------------------------

def processApps(target: str) -> None:
   OUTPUT_DIR = os.path.join("full", "cocohue")
   for appFile in appFiles:
      ogFilename = os.path.join(APP_DIR, appFile)
      destFilename = os.path.join(OUTPUT_DIR, appFile)
      processFile(ogFilename, destFilename, target)
      global totalProcessed
      totalProcessed += 1

def processDrivers(target: str) -> None:
   OUTPUT_DIR = os.path.join("full", "cocohue")
   for driverFile in driverFiles:
      ogFilename = os.path.join(DRIVER_DIR, driverFile)
      destFilename = os.path.join(OUTPUT_DIR, driverFile)
      processFile(ogFilename, destFilename, target)
      global totalProcessed
      totalProcessed += 1

#------------------------------
# MAIN:
# ------------------------------

def main(target: str):
   print("\n----------\nProcessing for target: " + target + "\n---------\n")
   processApps(target)
   processDrivers(target)
   print(f"\nDone. TOTAL: {totalProcessed} files processed.")



if __name__ == '__main__':
   if len(sys.argv) > 1:
      tgt = sys.argv[1]
      if (tgt.strip() in ALL_TARGET_NAMES):
         tgtVal = ALL_TARGET_NAMES[tgt.strip()]
         main(tgtVal)
      else:
         print(f"Invalaid target specified: {tgt}. Should be one of: {list(ALL_TARGET_NAMES.keys())}")
   else: 
      print(f"No target specified. Run script with parameter, e.g., COCOHUE. Possible parameters: {list(ALL_TARGET_NAMES.keys())}")