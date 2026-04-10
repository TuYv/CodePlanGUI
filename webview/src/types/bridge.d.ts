export interface Bridge {
  sendMessage: (text: string, includeContext: boolean) => void
  newChat: () => void
  onStart: (msgId: string) => void
  onToken: (token: string) => void
  onEnd: (msgId: string) => void
  onError: (message: string) => void
}

declare global {
  interface Window {
    __bridge: Bridge
  }
}

export {}
