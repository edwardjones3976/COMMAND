package com.command.reveille

import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Tags with an id the test chooses, built through the platform's own hidden `Tag.createMockTag`: three parameters up to API 33, and a fourth
 * (a `long` cookie) from API 34. The tag has no technologies, which is all the reader needs: it reads only the id.
 */
internal object MockTags {
    fun withId(vararg id: Int): Tag = withId(ByteArray(id.size) { id[it].toByte() })

    fun withId(id: ByteArray): Tag {
        val params = mutableListOf<ClassParameter<*>>(
            ClassParameter.from(ByteArray::class.java, id),
            ClassParameter.from(IntArray::class.java, IntArray(0)),
            ClassParameter.from(Array<Bundle>::class.java, arrayOf<Bundle>()),
        )
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) params.add(ClassParameter.from(Long::class.javaPrimitiveType, 0L))
        return ReflectionHelpers.callStaticMethod(Tag::class.java, "createMockTag", *params.toTypedArray())
    }
}
