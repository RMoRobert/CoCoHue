// Version 1.0.0

library (
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains field variables shared by many CoCoHue apps and drivers.",
   name: "CoCoHue_Constants_Lib",
   namespace: "RMoRobert"  /* "RMoRobert" for CoCoHue, "hubitat" for builtin */
)


// APP NAME and APP AND DRIVER NAMESPACES:
@Field static final String APP_NAME = "CoCoHue - Hue Bridge Integration"
//@Field static final String APP_NAME = "Hue Bridge Integration"

@Field static final String NAMESPACE = "RMoRobert" // "RMoRobert" for CoCoHue, "hubitat" for builtin
//@Field static final String NAMESPACE = "hubitat" // "RMoRobert" for CoCoHue, "hubitat" for builtin

@Field static final String DNI_PREFIX = "CCH" // "CCH" for CoCoHue, "Hue" for builtin
//@Field static final String DNI_PREFIX = "Hue"

// OTHER:

@Field static final String APIV1 = "V1"
@Field static final String APIV2 = "V2"
