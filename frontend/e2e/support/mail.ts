/** The local inbox. Mailpit speaks SMTP for mailer and serves this HTTP API; see docker-compose.yml. */
const mailpit = process.env.MAILPIT_URL ?? 'http://localhost:8025'

interface MailpitSearch {
  messages_count: number
  messages: { ID: string }[]
}

async function search(email: string): Promise<MailpitSearch> {
  const response = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`)
  if (!response.ok) throw new Error(`Mailpit answered ${response.status}`)
  return (await response.json()) as MailpitSearch
}

/** How many messages the inbox holds for one address. */
export async function mailCount(email: string): Promise<number> {
  return (await search(email)).messages_count
}

/**
 * The link in the newest message to an address, waiting for it: a mail travels from Keycloak through the broker and
 * mailer before it reaches the inbox, so it arrives a moment after the page says it was sent.
 */
export async function linkInMailTo(email: string, pattern: RegExp, timeoutMs = 30_000): Promise<string> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const found = await search(email)
    const id = found.messages[0]?.ID
    if (id) {
      const response = await fetch(`${mailpit}/api/v1/message/${id}`)
      const message = (await response.json()) as { Text: string }
      const link = pattern.exec(message.Text)?.[0]
      if (link) return link.replace(/\r/g, '')
    }
    if (Date.now() > deadline) throw new Error(`no mail with ${pattern} reached ${email} within ${timeoutMs} ms`)
    await new Promise((done) => setTimeout(done, 500))
  }
}
