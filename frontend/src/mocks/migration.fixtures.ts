/** A BPMN process whose call activities `call1..n` invoke the given processes in sequence (tests and demos). */
export function bpmn(processId: string, ...called: string[]): string {
  const calls = called.map((target, index) => `<callActivity id="call${index + 1}" calledElement="${target}"/>`).join('')
  const nodes = ['start', ...called.map((_, index) => `call${index + 1}`), 'end']
  const flows = nodes.slice(1).map((target, index) => `<sequenceFlow id="flow${index + 1}" sourceRef="${nodes[index]}" targetRef="${target}"/>`).join('')
  return `<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test"><process id="${processId}" isExecutable="true"><startEvent id="start"/>${calls}<endEvent id="end"/>${flows}</process></definitions>`
}
