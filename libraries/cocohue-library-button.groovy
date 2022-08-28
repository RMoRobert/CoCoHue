// Version 1.0.0

library (
   base: "driver",
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains fields with information on supported button devices.",
   name: "CoCoHue_Button_Lib",
   namespace: "RMoRobert"
)

/*
   List of supported button devices. Format:
   [
   <manufacturer_name>: [
         <model_id>: [

         ]
      ...
   ]
   ...
   ]
*/

@Field static final Map<String,List<Map<String,Object>>> supportedButtonDevices = 
[
   "Signify Netherlands B.V.": [
      "ZGPSWITCH": [ // Hue Tap
         numberOfbuttons: 4
      ],
      "RWL020": [ // Hue Dimmer (v1?)
         numberOfbuttons: 4
      ],
   ],
   "Phllips": [
      // TODO: repeat above for older bridge FW? But probably not needed with v2 API...
   ]
]