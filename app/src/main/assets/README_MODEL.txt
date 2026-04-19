MODEL REQUIRED
==============
File : depthanythingv2-vits-dynamic-quant.onnx  (~26 MB)
Source: https://github.com/combatwombat/tiefling/tree/main/site/public/models

Quick download
--------------
Windows:
  powershell -ExecutionPolicy Bypass -File scripts/download_model.ps1

Mac / Linux:
  bash scripts/download_model.sh

Manual download
---------------
1. Open the URL above in a browser.
2. Click "depthanythingv2-vits-dynamic-quant.onnx" → Download.
3. Copy it to THIS directory (app/src/main/assets/).

Model contract (used by DepthEstimator.kt)
------------------------------------------
  Input  name : "image"
  Input  shape: [1, 3, 518, 518]  float32  NCHW  values in [0, 1]
  Output name : "depth"
  Output shape: [1, 518, 518]     float32  (larger value = farther away)
