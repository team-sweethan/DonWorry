import { App, Modal } from 'antd'

/** 반드시 <App> 내부에서 사용할 것: 전역 Toast/Confirm 스타일 일치. */
export function useFeedback() {
  const { message, modal } = App.useApp()
  return {
    toast: {
      success: (text: string) => message.success({ content: text, duration: 3 }),
      error: (text: string) => message.error({ content: text, duration: 4 }),
      info: (text: string) => message.info({ content: text, duration: 3 }),
    },
    confirmDanger: ({
      title,
      content,
      onConfirm,
    }: {
      title: string
      content: string
      onConfirm: () => void | Promise<void>
    }) => {
      modal.confirm({
        title,
        content,
        okText: '확인',
        cancelText: '취소',
        okButtonProps: { danger: true },
        centered: true,
        width: 480,
        onOk: onConfirm,
      })
    },
    confirm: (config: Parameters<typeof Modal.confirm>[0]) => modal.confirm(config),
  }
}
