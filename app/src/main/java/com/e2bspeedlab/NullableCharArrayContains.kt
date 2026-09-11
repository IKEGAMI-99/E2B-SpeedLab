package com.e2bspeedlab

/** Keeps punctuation pacing null-safe when a FLASH unit is unexpectedly empty. */
operator fun CharArray.contains(element: Char?): Boolean =
    element != null && any { it == element }
