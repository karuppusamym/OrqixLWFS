const query = new URLSearchParams(location.search)
const mode = query.get('component') === 'tasks' ? 'tasks' : 'authoring'
const element = document.getElementById('component')
try {
  const component = await import(`/components/${mode}/orqix-${mode}.js`)
  component.mount(element, { apiVersion: 'v1', workspaceId: query.get('workspace') ?? '', workflowId: query.get('workflow') || undefined })
} catch (error) {
  const message = document.createElement('p'); message.role = 'alert'; message.textContent = error instanceof Error ? error.message : 'Component could not load.'; element.appendChild(message)
}
