# URUTAU AI administrativa

A assistente flutuante consulta os dados do painel e prepara operações para revisão. A conversa usa a sessão autenticada e é independente da interpretação no gerador com IA.

## Matriz de capacidades

| Domínio | Leitura | Escrita com confirmação | Limites |
| --- | --- | --- | --- |
| Clientes | Busca, estado, histórico, último registro, totais e WhatsApp | Cadastrar, alterar nome ou telefone, desativar, reativar e vincular registro histórico | Ambiguidades exigem nome mais preciso ou telefone. O painel oferece a revisão de duplicidades. |
| Orçamentos | Listas, períodos, linha a linha, tabela, total e maior valor | Criar rascunho, duplicar a partir de orçamento ou pedido e emitir um rascunho revisado | As listas da conversa são resumidas. Ações abrem o registro existente. |
| Rascunhos | Listar, recuperar e estimar, sempre pelo administrador atual | Criar, editar itens e observações, vincular cliente, transferir e descartar | A revisão pendente do gerador deve ser concluída antes da edição pela assistente. |
| Pedidos | Estado, atrasos, prazo, confirmação, entrega e eventos | Confirmar, cancelar, entregar e atualizar prazo ou observações nos estados permitidos | Operações abrangem um registro por proposta. Cancelamento pede justificativa. |
| Catálogo | Modelos ativos e inativos, gramaturas, cores e disponibilidade | Cadastrar, renomear, adicionar combinações e alterar estados de modelos e variantes | Gramaturas são inteiras em gramas. Até 100 combinações por proposta. No chat, indique exceções como “exceto 15g cor 03”. No painel, refine os pares pela prévia visual. |
| Preços | Faixas por modelo e tabela e valores por variante | Alterar ou ajustar preços em lote para modelos mencionados | Uma tabela por proposta. Todos os preços afetados são exibidos. |
| Configurações | Mínimo vigente de lojista | Alterar o mínimo | A configuração comercial disponível é o mínimo de lojista. |
| Documentos | Abrir, baixar e compartilhar PDF existente. Abrir prévia de rascunho. | Emissão definitiva depende da aprovação da proposta | O administrador controla o compartilhamento. |
| Indicadores | Novos clientes, conversão, confirmações, entregas, valores comerciais, contagens, rankings e comparação mensal | — | Períodos usam America/Sao_Paulo. Os valores representam pedidos confirmados vigentes. |
| Produção | Fila FIFO, primeiro pedido, iscas demandadas, prazos e detalhes de fabricação | — | A consulta usa os itens históricos. O registro individual de fabricação fica para uma etapa posterior. |
| Navegação | Abrir cliente, pedido, modelo, rascunho ou seção do painel | — | Atalhos apresentam os registros acessíveis pela sessão administrativa. |

Os relatórios exibem totais conforme a data do evento considerado, como emissão, confirmação ou entrega. Consultas resumidas exibem uma amostra e indicam a tela em que o administrador pode consultar os demais resultados. Estoque, registro de fabricação, finanças e infraestrutura ficam fora das ferramentas.

## Ferramentas e parâmetros

O orquestrador interpreta a solicitação e chama um serviço Java específico. Os parâmetros de negócio são validados no servidor. Em solicitações compatíveis, a seleção de intenção é local. Quando necessário, o Gemini recebe vocabulário comercial limitado para selecionar um identificador previamente conhecido. A elaboração de itens reaproveita o interpretador híbrido e o catálogo dinâmico.

| Ferramentas | Parâmetros comerciais |
| --- | --- |
| `CUSTOMERS`, `CUSTOMER_HISTORY`, `CUSTOMER_CREATE`, `CUSTOMER_EDIT`, `CUSTOMER_ACTIVATE`, `CUSTOMER_LINK` | Nome ou telefone, ID resolvido localmente, estado, novo nome ou telefone, referência comercial |
| `RECORD`, `ORDERS`, `QUOTES`, `ORDER_CONFIRM`, `ORDER_CANCEL`, `ORDER_DELIVER`, `ORDER_TERMS` | Número, estado, intervalo, prazo, observação ou justificativa |
| `DRAFTS`, `DRAFT`, `DRAFT_CREATE`, `DRAFT_DUPLICATE`, `DRAFT_UPDATE`, `DRAFT_DISCARD`, `ISSUE` | UUID de rascunho do administrador, origem, cliente, variantes, quantidades e instrução |
| `CATALOG`, `PRODUCT_CREATE`, `PRODUCT_UPDATE` | Produto, gramaturas, códigos de cor, estado, nome e preços iniciais |
| `PRICES`, `PRICE_UPDATE`, `SETTINGS`, `SETTINGS_UPDATE` | Modelo, filtro de variante, tabela, valor decimal e mínimo em unidades |
| `INDICATORS` | Período, comparação mensal e ranking |
| `PRODUCTION_QUEUE`, `PRODUCTION_FIRST`, `PRODUCTION_DETAILS`, `PRODUCTION_LINK` | Filtro de prazo, pesquisa de cliente ou número de pedido quando aplicável. Consultas de leitura pelo [serviço de produção](producao.md). |

## Confirmação e auditoria

Propostas ficam vinculadas à sessão e ao administrador. O cartão apresenta a operação, o registro, os valores aplicáveis e as consequências. A proposta expira em cinco minutos e é consumida quando confirmada ou cancelada. O endpoint de decisão aceita apenas o identificador e a escolha. O conteúdo aprovado permanece no servidor e as regras comerciais e versões são revalidadas na execução. A transação registra a correlação da operação junto com seus efeitos.

A migração V10 cria a auditoria compartilhada. O campo de origem identifica alterações da URUTAU AI e do painel de catálogo. Dados de contato completos e textos comerciais livres permanecem nos registros de negócio apropriados. A auditoria conserva identificadores, estados ou preços e o resultado. A V11 adiciona datas de criação e atualização para o catálogo. Registros legados sem data conhecida mantêm o campo da criação em branco.

Os endpoints da assistente exigem papel administrativo, cookie de sessão e CSRF para operações de escrita. As respostas usam `no-store`. A sessão mantém até 40 mensagens, aceita até 18 solicitações por minuto e limita cada mensagem a 1.000 caracteres. A seleção de ferramentas aceita apenas identificadores registrados. As requisições externas usam o timeout do adaptador. As operações comerciais não usam respostas brutas do provedor como código, SQL ou parâmetros de infraestrutura.

## Exemplos

- “Cadastre um novo modelo chamado Isca Exemplo, com gramaturas de 10g, 15g e 20g, cores 01 até 06, preço de lojista R$ 19,00 e consumidor final R$ 30,00.”
- “Adicione a cor 10 em todas as gramaturas da Urunaja.”
- “Altere o preço de lojista da Urunaja para R$ 18,00.”
- “Qual foi o último pedido do Marcos?” e “Quanto ele já comprou?”
- “Duplique o orçamento número 00015.”
- “Mostre os pedidos em produção.”
- “Mostre os detalhes de fabricação do primeiro pedido.”
- “Quais pedidos possuem prazo para hoje?”
- “Quanto vendemos neste mês?”
