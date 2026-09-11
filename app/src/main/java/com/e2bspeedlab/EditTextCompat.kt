package com.e2bspeedlab

import android.widget.EditText

/** Kotlin-friendly bridge for the overloaded TextView#setSingleLine API. */
internal var EditText.singleLine: Boolean
    get() = maxLines == 1
    set(value) {
        setSingleLine(value)
    }
