package com.phlox.tvwebbrowser.utils.activemodel

/*
* If you implement this interface then make sure that you manually marked as needless
* (@see com.phlox.tvwebbrowser.utils.statemodel.ActiveModelsRepository#markAsNeedless())
* all your "active models" when they are not needed (on your component onDestroy() or similar)
 */
interface ActiveModelUser