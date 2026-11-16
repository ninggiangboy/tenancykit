plugins {
    id("layered-module-conventions")
}

// The rewards context. It has its package layout and its layers, and no code yet: its tables, use cases and adapters arrive
// with the phase that owns them, so the build declares no dependency it does not read.
