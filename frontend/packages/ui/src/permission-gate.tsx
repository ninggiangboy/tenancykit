/** Hides a control the signed-in user may not use. The backend still decides; this only keeps the screen honest. */
export function PermissionGate({
  allowed,
  children,
  fallback = null,
}: {
  allowed: boolean
  children: React.ReactNode
  fallback?: React.ReactNode
}) {
  return <>{allowed ? children : fallback}</>
}
