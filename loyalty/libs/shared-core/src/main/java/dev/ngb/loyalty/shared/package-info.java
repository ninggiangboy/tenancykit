/**
 * The shared core of loyalty: the permission codes the app enforces and the header names of its signed deliveries. No
 * business rule lives here.
 *
 * <p>The suite's id generator is visible from this module rather than redefined in it: {@code shared-core} exports
 * {@code dev.ngb:core-kernel}, so every layer of every context sees {@code dev.ngb.core.runtime.IdGenerator} and the app
 * wires the one implementation — {@code UuidV7IdGenerator} — in {@code app-boot}. A second id type here would be a
 * second source of ids for one app.
 */
package dev.ngb.loyalty.shared;
