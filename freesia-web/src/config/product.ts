export const product = Object.freeze({
  name: 'Freesia',
  adminName: 'Freesia Admin',
  description: '让管理更清晰，让工作更从容。',
  logo: '/brand/freesia-mark.svg',
  repositoryUrl:
    import.meta.env.VITE_PRODUCT_REPOSITORY_URL || 'https://github.com/VirtualGemini/freesia',
  docsUrl: import.meta.env.VITE_PRODUCT_DOCS_URL || ''
})
