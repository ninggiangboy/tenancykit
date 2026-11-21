import { type ClassValue, clsx } from 'clsx'
import { extendTailwindMerge } from 'tailwind-merge'

/**
 * The type scale `theme.css` defines (`--text-<name>`). Without it tailwind-merge reads `text-body-strong` as a text
 * colour and drops the colour next to it, so a primary button lost `text-primary-foreground` and its label.
 */
export const TYPE_SCALE = [
  'metric',
  'title',
  'figure',
  'heading',
  'body',
  'body-strong',
  'small',
  'caption',
  'code',
] as const

const merge = extendTailwindMerge({
  extend: { theme: { text: [...TYPE_SCALE] } },
})

/** Joins class names and lets a caller's utility win over a component default. */
export function cn(...inputs: ClassValue[]): string {
  return merge(clsx(inputs))
}
