// !WITH_NEW_INFERENCE
open class Super<T> {
    inner open class Inner {
    }
}

class Sub : Super<String>() {
    // TODO: it would be nice to have a possibility to omit explicit type argument in supertype
    inner class SubInner : Super<String>.<!NI;DEBUG_INFO_UNRESOLVED_WITH_TARGET, NI;UNRESOLVED_REFERENCE_WRONG_RECEIVER!>Inner<!>() {}
}
