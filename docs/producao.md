# Produção na versão 0.3.0

A página **Operacional → Produção** mostra as necessidades de fabricação dos pedidos comercialmente confirmados que continuam em produção. A página **Pedidos** mantém as ações comerciais. Use **Abrir em Pedidos** nos detalhes da fila para acompanhar, cancelar ou registrar a entrega pelo fluxo existente.

## Fila FIFO

A fila é derivada de `commercial_order`, `commercial_quote` e `commercial_quote_item`. Cada pedido ativo corresponde ao orçamento confirmado, com identificador próprio no cadastro comercial. A condição de entrada é o estado `CONFIRMADO` e a ausência de entrega. Pendentes, cancelados e entregues ficam no histórico correspondente.

O banco calcula a posição com `ROW_NUMBER()` sobre a data original de confirmação e, em caso de empate, sobre o número do pedido, ambos crescentes. Pesquisa, filtros de prazo e paginação são aplicados depois dessa ordenação. A posição continua sendo a da fila completa. Os pedidos anteriores à atualização entram automaticamente na mesma ordem.

As consultas são de leitura. A fila não cria registros próprios. Os indicadores e a página de resultados usam uma leitura consistente. O horário de confirmação exibido usa o mesmo mapeamento de datas da página Pedidos.

## Prazo e indicadores

A referência comercial é **America/Sao_Paulo**. Um prazo anterior à data local está atrasado. A data atual vence hoje. Os dois dias corridos seguintes aparecem como prazo próximo. Os demais prazos futuros estão dentro do prazo. Um campo vazio aparece como **Sem prazo definido**.

O cadastro comercial oferece uma data prevista sem tipo específico de prazo. Produção apresenta essa informação como **prazo comercial previsto**. Alertas e filtros preservam o FIFO. Os indicadores contam pedidos ativos, iscas demandadas, pedidos atrasados, próximos e vencendo hoje, e identificam o primeiro pedido. As iscas demandadas são a soma dos itens dos pedidos ativos.

## Lista de fabricação

Os detalhes usam as descrições comerciais gravadas na emissão. O servidor consolida linhas repetidas da mesma combinação e descrição dentro do pedido. A lista mantém modelo, gramatura e cor históricos e sinaliza a disponibilidade atual sem alterar os registros originais. Os valores comerciais são os gravados na emissão.

A resposta de fabricação traz o identificador estável do modelo e da variante, além da gramatura e da cor registradas. A estrutura permite agregar necessidades de diversos pedidos numa etapa posterior. O registro de unidades fabricadas e a distribuição dessas unidades ficam para as próximas versões.

## Atualização e acesso

O módulo atualiza ao abrir a página, ao voltar para ela e a cada 30 segundos enquanto a aba está visível. **Atualizar fila** consulta a versão atual sob demanda. Perda de conexão usa o fluxo do PWA e falhas de consulta mostram indisponibilidade. O PWA guarda somente os recursos públicos já definidos por sua lista de cache.

`GET /api/production` retorna indicadores e até 12 pedidos por página. `GET /api/production/{number}` retorna os detalhes de um pedido ativo. Ambos exigem autenticação administrativa e usam `Cache-Control: no-store`. As confirmações, cancelamentos, prazos e entregas continuam sendo gravações dos serviços comerciais com seus controles de acesso e histórico.

A URUTAU AI usa as mesmas consultas Java para fila, primeiro pedido, prazos e lista de fabricação. Os comandos previstos são interpretados localmente. Consulte a [matriz da assistente](assistente.md).
