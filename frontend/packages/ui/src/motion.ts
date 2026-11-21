/** Enter and leave of anything Radix positions beside its trigger: a menu or a popover grows from the trigger. */
export const POPPER_MOTION =
  'origin-(--radix-popper-transform-origin) data-[state=closed]:animate-pop-out data-[state=open]:animate-pop-in'

/** A tooltip's states are `delayed-open` and `instant-open` rather than `open`. */
export const TOOLTIP_MOTION =
  'origin-(--radix-popper-transform-origin) data-[state=closed]:animate-pop-out data-[state=delayed-open]:animate-pop-in data-[state=instant-open]:animate-pop-in'

/** The full 44px touch target on a phone, for a control whose drawn size is smaller. */
export const PHONE_ROW = 'max-md:min-h-(--size-touch-target)'
