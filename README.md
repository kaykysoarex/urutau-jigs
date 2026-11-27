# URUTAU JIGS · Gerador de orçamentos

Sistema administrativo para montar, revisar, emitir e consultar orçamentos de iscas artificiais. A versão inicial inclui o gerador manual, o URUTAU AI, a administração do catálogo e dos preços, a configuração do mínimo para lojistas e o histórico de PDFs.

## Executar no Windows

O JAR distribuído requer Java 21. Para compilar o código-fonte, instale também o Maven 3.9 ou mais recente.

1. Abra o PowerShell na pasta do código-fonte, `urutau-jigs`. Se for a primeira instalação, copie o exemplo e preencha os dois acessos administrativos. Em uma atualização, mantenha as variáveis e senhas do seu `.env`.

   ```powershell
   if (-not (Test-Path -LiteralPath '.env')) {
       Copy-Item -LiteralPath '.env.example' -Destination '.env'
   }
   notepad .env
   ```

2. Carregue a configuração no processo atual do PowerShell e execute a aplicação. Esse carregamento lê o valor literal depois do primeiro sinal de igual. Use uma variável por linha, sem aspas externas.

   ```powershell
   Get-Content -Encoding UTF8 .env | ForEach-Object {
       if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
           [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
       }
   }
   java -jar ..\urutau-jigs-0.2.3.jar
   ```

3. Abra [o painel local](http://127.0.0.1:8080/) e entre com uma das contas. Confira os valores em **Tabela de preços**. Os preços iniciais do catálogo oficial são cadastrados pelo Flyway. O H2 guarda os registros em `data/` da pasta onde a aplicação foi iniciada.

Use `mvn spring-boot:run` para executar pelo código-fonte. Use `mvn clean verify` para executar os testes e gerar o JAR em `target/urutau-jigs-0.2.3.jar`. Em Linux, exporte as mesmas variáveis no ambiente do processo e execute o JAR com Java 21.

## Instalar o URUTAU JIGS como aplicativo

Abra a URL HTTPS do serviço no dispositivo em que deseja instalar. Para testes locais no computador, use `http://127.0.0.1:8080/`. O Railway usa o domínio HTTPS já configurado para o serviço e as mesmas variáveis de ambiente.

- **Android.** Abra o sistema no Chrome. No menu lateral do painel, escolha **Instalar aplicativo** quando a opção aparecer. Também é possível instalar pelo menu do Chrome em **Instalar e criar atalho → Instalar**. [Ajuda do Chrome no Android](https://support.google.com/chrome/answer/9658361?co=GENIE.Platform%3DAndroid&hl=pt-BR)
- **iPhone.** Abra a URL no Safari e toque em **Compartilhar → Adicionar à Tela de Início**. Ative **Abrir como App** se essa opção aparecer e toque em **Adicionar**. O menu lateral do painel mostra essas instruções. [Suporte da Apple](https://support.apple.com/en-lamr/guide/iphone/iphea86e5236/ios)
- **Windows.** Use **Instalar aplicativo** no painel, o ícone de instalação na barra de endereços ou o menu do navegador. No Chrome, a opção está em **Transmitir, salvar e compartilhar**. No Edge, procure em **Mais ferramentas → Aplicativos**. [Ajuda do Chrome](https://support.google.com/chrome/answer/9658361?co=GENIE.Platform%3DDesktop&hl=pt-BR) · [Suporte do Edge](https://support.microsoft.com/pt-br/edge/install-manage-or-uninstall-apps-in-microsoft-edge)

O aplicativo instalado abre pela tela inicial ou pelo menu de aplicativos do dispositivo. Se o navegador isolar os cookies da versão instalada, faça o primeiro login normalmente. Em caso de falta de conexão, o painel pausa as operações até o servidor voltar. Use **Tentar novamente** na tela de indisponibilidade.

O Service Worker usa a rede para abrir o painel e mantém no Cache Storage somente os recursos públicos da lista em `service-worker.js`. Ao publicar uma nova versão, atualize o nome do cache nesse arquivo. O aplicativo oferece **Atualizar aplicativo** quando a nova versão está pronta e aguarda a conclusão do orçamento em andamento antes de permitir a atualização.

## Banco de dados e acesso compartilhado

Para uso pela equipe, execute uma instância da aplicação, com ambos os administradores usando o mesmo endereço e o mesmo banco. Crie um banco MySQL com codificação `utf8mb4` e um usuário dedicado ao schema. Configure `DB_URL`, `DB_USER` e `DB_PASSWORD`. A conta da aplicação precisa poder executar as migrações Flyway e ler e gravar as tabelas do schema.

```sql
CREATE DATABASE urutau_jigs CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

O endereço padrão é somente local. Para acesso compartilhado, publique a aplicação atrás de um proxy HTTPS, mantenha o serviço de aplicação acessível apenas ao proxy e configure `SESSION_COOKIE_SECURE=true`. Defina `SERVER_ADDRESS` se o proxy precisar acessar uma interface diferente de loopback. Habilite `FORWARD_HEADERS_STRATEGY=framework` quando o proxy confiável fizer a terminação TLS e filtrar cabeçalhos encaminhados. Use backups do banco para preservar catálogo, preços e histórico.

As contas são configuradas por variáveis de ambiente. A aplicação valida os dois acessos na inicialização e usa hashes BCrypt com salt para detectar mudanças nas credenciais. Os formulários usam sessão e proteção CSRF.

### Autenticação persistente

O Spring Security ativa a autenticação persistente no primeiro login após a atualização. O banco guarda o identificador e o hash do token. O cookie é `HttpOnly` e `SameSite=Lax`, com `Secure` no Railway. Ao voltar pelo mesmo navegador e endereço com um cookie válido, o sistema recria a sessão e abre o painel. Isso funciona após fechar o navegador, reiniciar o dispositivo ou publicar um novo deploy com o mesmo banco.

A validade padrão é **90 dias**, renovada a cada autenticação automática. Para personalizar, defina `REMEMBER_ME_DAYS` com um inteiro positivo no `.env` local ou nas variáveis do serviço no Railway.

Ao selecionar **Sair**, a aplicação encerra a sessão e revoga os tokens persistentes daquela conta. Alterar o nome de acesso ou a senha em `ADMIN_1_*` ou `ADMIN_2_*` também revoga os tokens da conta correspondente na próxima inicialização. O Flyway adiciona as tabelas de autenticação ao H2 local ou ao MySQL, preservando os registros comerciais. A funcionalidade usa as credenciais do banco e as variáveis administrativas já configuradas.

## Publicar no Railway

### Criar o projeto e conectar o GitHub

1. No painel do Railway, escolha **New Project → Empty Project**. Use o ambiente `production` e confirme que o workspace está no **Free** ou **Trial** e tem crédito disponível. A aplicação e o MySQL consomem o crédito do mesmo workspace. Quando o crédito do Free acaba, os serviços param até a renovação da cota. [Plano Free e Trial](https://docs.railway.com/pricing/free-plan)
2. No canvas do projeto, escolha **New → Database → MySQL**. Mantenha o serviço com o nome `MySQL` e na rede privada. No mesmo ambiente, crie um **Empty Service** com o nome `urutau-jigs` para a aplicação. Use as credenciais do banco geradas pelo Railway. [MySQL no Railway](https://docs.railway.com/databases/mysql)
3. No serviço `urutau-jigs`, abra **Variables → RAW Editor** e insira o bloco abaixo. Substitua os marcadores pelos dois nomes de acesso e pelas senhas correspondentes. Use nomes distintos e senhas de pelo menos 12 caracteres, até 72 bytes. Preencha a chave do Gemini para interpretar mensagens livres. Guarde os valores reais apenas nas variáveis do serviço. [Variáveis de serviço](https://docs.railway.com/variables)

   ```dotenv
   DB_URL=jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?connectionTimeZone=UTC
   DB_USER=${{MySQL.MYSQLUSER}}
   DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}
   ADMIN_1_USERNAME=SUBSTITUA_USUARIO_1
   ADMIN_1_PASSWORD=SUBSTITUA_SENHA_1
   ADMIN_2_USERNAME=SUBSTITUA_USUARIO_2
   ADMIN_2_PASSWORD=SUBSTITUA_SENHA_2
   AI_PROVIDER=gemini
   GEMINI_API_KEY=SUBSTITUA_CHAVE_GEMINI
   ```

   As expressões `${{MySQL.NOME_DA_VARIAVEL}}` são referências do Railway e devem ser coladas como estão. Para personalizar a validade da autenticação persistente, inclua `REMEMBER_ME_DAYS` nas variáveis do serviço. `GEMINI_MODEL` é opcional e usa `gemini-3.5-flash-lite` quando vazio. Para escolher outro provedor, siga a seção [Provedor alternativo](#provedor-alternativo).

4. Em **Settings → Source → Connect Repo**, conecte `kaykysoarex/urutau-jigs`. Se o GitHub solicitar a instalação do aplicativo Railway, conceda acesso ao repositório oficial. Selecione `main` como branch de deploy e mantenha **Autodeploy** habilitado. Deixe o **Root Directory** na raiz do repositório e **Wait for CI** desabilitado. O Railway detecta o `Dockerfile` na raiz e a construção da imagem executa os testes Maven. Deixe o **Start Command** no padrão da imagem. Cada push na `main` aciona um novo deploy. [Repositório oficial](https://github.com/kaykysoarex/urutau-jigs) · [Autodeploy pelo GitHub](https://docs.railway.com/deployments/github-autodeploys) · [Detecção do Dockerfile](https://docs.railway.com/builds/dockerfiles)

### Aplicar e publicar

1. Em **Settings → Deploy** do serviço da aplicação, configure **Healthcheck Path** como `/login.html` e mantenha **Healthcheck Timeout** em **300 segundos**. O `Dockerfile` ativa o perfil `railway` e o Spring Boot escuta em `0.0.0.0` na porta dinâmica `PORT` injetada pelo Railway. [Verificação de saúde e porta](https://docs.railway.com/deployments/healthchecks)
2. Revise as alterações pendentes no canvas e clique em **Deploy**. Aguarde a aplicação ficar **Active**. O Flyway cria as tabelas e carrega o catálogo inicial no MySQL na primeira inicialização. [Aplicar alterações](https://docs.railway.com/deployments/staged-changes)
3. No serviço `urutau-jigs`, abra **Settings → Networking → Public Networking → Generate Domain**. Use a porta detectada pelo Railway e abra o domínio gerado com `https://`. O Railway emite o certificado e termina o TLS na borda. O perfil da aplicação respeita o cabeçalho de protocolo encaminhado e ativa o atributo `Secure` do cookie de sessão. [HTTPS e domínio público](https://docs.railway.com/networking/public-networking) · [Cabeçalhos do proxy](https://docs.railway.com/networking/public-networking/specs-and-limits)
4. Teste o login com as duas contas e abra o catálogo. Para conferir o Gemini, envie uma mensagem livre de teste na **URUTAU AI**, como `Quero três Spinner de 12g na cor 03`.

Mantenha uma réplica da aplicação, pois as sessões administrativas e as conversas em andamento ficam em memória. Após um novo deploy, a autenticação persistente abre uma nova sessão. Os dados gravados no MySQL persistem após novos deploys. O arquivo H2 local, seus clientes, tabelas de preço e orçamentos permanecem na instalação local. Uma eventual migração desses registros exige uma transferência separada.

## Catálogo e preços

- A carga inicial contém nove modelos e 124 combinações de gramatura e cor. A migração utiliza os dados oficiais verificados. [Catálogo](https://catalogo-urutau.vercel.app/) · [Dados canônicos](https://catalogo-urutau.vercel.app/catalog-data.js) · [Validação da página de origem](https://catalogo-urutau.vercel.app/script.js)
- Cada combinação guarda os preços de consumidor final e lojista de forma independente. Campos em branco representam preço pendente. A emissão exige um preço na tabela aplicada para cada item.
- Com o mínimo padrão de 30 unidades, orçamentos de 1 a 29 iscas usam a tabela de consumidor final e orçamentos a partir de 30 iscas usam a tabela de lojista. O back-end soma as unidades de todas as combinações e aplica a tabela correspondente a todos os itens. Se a quantidade voltar a ficar abaixo do mínimo, a tabela de consumidor final volta a valer. Altere o mínimo em **Configurações**.
- A remoção cadastral preserva o registro da combinação como inativo. Reativar a mesma combinação restaura o seu cadastro com o mesmo identificador.
- A emissão compara a revisão com os dados atuais do catálogo antes de gravar o orçamento. Cada emissão grava em uma transação os dados do cliente e os valores e descrições aplicados. A numeração vem do banco, e cada orçamento começa com status **Pendente**. O PDF é gerado a partir desses dados históricos.

### Preços unitários iniciais

| Modelo | Consumidor final | Lojista |
| --- | ---: | ---: |
| Urumax G1 | R$ 27,00 | R$ 16,00 |
| Urumax G2 | R$ 29,00 | R$ 18,00 |
| Urunaja | R$ 27,00 | R$ 17,00 |
| Jig Urutau | R$ 26,00 | R$ 16,00 |
| Jig Articulado | R$ 26,00 | R$ 16,00 |
| Jig Peixinho | R$ 26,00 | R$ 16,00 |
| Micro Jig | R$ 26,00 | R$ 16,00 |
| Jig Bolinha | R$ 26,00 | R$ 16,00 |
| Spinner | R$ 26,00 | R$ 16,00 |

A migração Flyway V5 preenche os campos vazios de cada tabela nas gramaturas e cores já cadastradas para esses modelos. Preços preenchidos são preservados, e divergências em relação à tabela inicial aparecem no log da migração. No Railway, o primeiro deploy da 0.1.7 aplica a V5 com a configuração de banco já existente.

Edite os valores vigentes em **Tabela de preços**. O banco é a fonte usada pelo Java para orçamentos manuais e revisados a partir da URUTAU AI. Reinícios e novos deploys preservam as alterações do painel, e PDFs emitidos mantêm os valores registrados na emissão. A informação de que o frete está fora do orçamento aparece no resumo.

## Navegação comercial e URUTAU AI

O menu **Comercial** inclui Visão geral, Novo orçamento manual, Novo orçamento com IA, Histórico de orçamentos, Pedidos e Clientes. O gerador com IA interpreta a mensagem, exibe as pendências, deixa revisar e corrigir as combinações e emite o orçamento na própria página. O gerador manual usa o mesmo editor, resumo, cálculo Java e PDF.

Os rascunhos mostram a origem Manual ou Com IA. Ao trocar de gerador, a aplicação sincroniza o trabalho e pode retomar o rascunho estacionado naquele modo durante a navegação. A abertura pela lista direciona ao gerador de origem. A transferência explícita conserva o identificador do rascunho. Rascunhos antigos com itens de IA pendentes abrem no modo IA. Os demais abrem no modo manual. A origem fica no próprio conteúdo do rascunho, usando a estrutura existente.

O formulário de orçamento solicita nome e, opcionalmente, telefone. Cidade e UF foram retiradas das telas, das buscas e dos novos PDFs. As colunas e os dados históricos permanecem no banco para preservar os documentos anteriores.

Abra a **URUTAU AI** pelo botão flutuante para consultar totais por estado comercial, modelos, gramaturas, preços vigentes e o mínimo de lojista, além de receber orientações da página atual. Essas respostas usam consultas de leitura ao Java. O Gemini continua disponível para interpretar pedidos em linguagem livre no gerador com IA. A conversa da assistente fica na sessão autenticada, pode ser retomada após minimizar e tem uma ação de reinício. Consultas a clientes específicos e alterações de registros exigem a página correspondente.

## Gerador manual

No gerador manual, escolha o modelo e a gramatura. Informe a quantidade padrão por cor e marque uma ou mais cores do catálogo ou use **Selecionar todas**. Ajuste as quantidades de cada cor diretamente na grade. Para aplicar um novo valor a todas as cores já marcadas, use **Aplicar quantidade padrão**.

Confira os contadores de cores e unidades e clique em **Adicionar ao orçamento**. Se alguma combinação já estiver no orçamento, sua quantidade será somada na linha existente. O sistema atualiza o total e aplica a tabela de consumidor final ou de lojista conforme o mínimo cadastrado em **Configurações**.

Em telas estreitas, os itens do orçamento, o histórico, o catálogo e a tabela de preços se adaptam à largura disponível. O resumo segue o fluxo da página para que os campos e as ações fiquem acessíveis ao abrir o teclado. Abra o menu lateral pelo botão do cabeçalho e feche-o pelo próprio menu, pelo fundo ou selecionando uma página.

## Rascunhos de orçamento

O gerador salva após cerca de um segundo de inatividade. São salvos os dados do cliente, as observações, os itens adicionados, as seleções ainda em edição e os itens da URUTAU AI prontos para revisão. O indicador **Rascunho salvo automaticamente** aparece após a confirmação do servidor. As observações ficam no rascunho e não aparecem no PDF.

No gerador, use **Continuar o último orçamento** ou **Visualizar rascunhos**. Cada administrador acessa seus próprios rascunhos, inclusive em outro dispositivo após entrar na mesma conta. **Iniciar novo orçamento** preserva os rascunhos anteriores. Para remover um deles, use **Descartar** e confirme. Se dois dispositivos editarem o mesmo rascunho, escolha **Carregar versão mais recente** ou **Salvar em outro rascunho** para preservar sua edição.

Ao recuperar a edição, o sistema Java usa os preços do catálogo e o limite comercial vigentes. O gerador sinaliza mudanças nas condições e combinações indisponíveis para revisão. Os rascunhos ficam fora do histórico, e a numeração comercial é atribuída na emissão definitiva. Uma emissão confirmada encerra o rascunho e limpa o gerador de origem imediatamente. O modal continua exibindo o orçamento emitido. Se a resposta for inconclusiva, os dados ficam no formulário e **Tentar confirmar emissão** repete a mesma solicitação para recuperar o resultado com segurança. A prévia mantém a edição atual.

Em caso de perda da conexão, mantenha o aplicativo aberto até a sincronização. A tentativa é retomada quando a rede volta e também pode ser acionada em **Tentar novamente**. Alterações pendentes ficam em memória e podem ser perdidas se você fechar o aplicativo. O sistema armazena os rascunhos no MySQL do Railway ou no H2 local. A migração Flyway V6 é aplicada na inicialização e não exige novas variáveis de ambiente.

## Pré-visualização do PDF

Com pelo menos um item no orçamento manual ou revisado pela URUTAU AI, clique em **Pré-visualizar PDF** para abrir ou baixar o rascunho. Os campos monetários sem valor aparecem como **A definir**. A identificação **PRÉVIA — SEM VALIDADE COMERCIAL** aparece em todas as páginas. O rascunho não é gravado no histórico e não recebe número oficial. Para **Emitir orçamento**, cadastre todos os preços da tabela aplicada e informe o nome do cliente.

## Compartilhar um orçamento pelo WhatsApp

Após a confirmação da emissão pelo servidor, o modal **Orçamento emitido!** abre na tela atual. Ele exibe número, cliente, quantidade, tabela e valor total. Use **Enviar pelo WhatsApp** para revisar a mensagem antes de compartilhar. Também é possível visualizar ou baixar o PDF nesse modal. **Compartilhar pelo WhatsApp** continua disponível em **Meus orçamentos** e na visualização do PDF emitido. Use **Copiar mensagem** para colar o texto na conversa, se necessário.

- Em navegadores com suporte ao compartilhamento de arquivos, selecione **Abrir menu de compartilhamento** e escolha o WhatsApp e o contato. O sistema prepara o PDF definitivo com o nome original. Se o aplicativo abrir apenas o arquivo, cole a mensagem copiada na conversa. [Web Share API](https://www.w3.org/TR/web-share/)
- Nos demais navegadores, selecione **Baixar PDF** e **Abrir WhatsApp Web**. O texto editado é preenchido na conversa do telefone salvo, quando ele possui formato válido. Para números fora do Brasil, use o prefixo `+` seguido do código do país. Se não houver um número utilizável, escolha o contato no WhatsApp. Anexe o PDF manualmente e confirme o envio. [Clique para conversa no WhatsApp](https://faq.whatsapp.com/5913398998672934/?locale=pt_BR)

O sistema compartilha somente PDFs emitidos e protegidos pelo login. O compartilhamento conserva a numeração, os valores e o status do orçamento. Ele dispensa serviços externos de automação, chaves adicionais e mudanças na configuração do Railway.

## Gerenciamento de clientes

Abra **Clientes** para cadastrar, pesquisar, editar, desativar ou reativar um cadastro compartilhado entre as contas administrativas. É possível cadastrar clientes sem telefone. Quando informado, o telefone é normalizado para busca e WhatsApp. Se um número já estiver cadastrado, escolha o cadastro existente ou confirme expressamente que o telefone é compartilhado. Nomes iguais são permitidos.

No gerador manual ou nos itens revisados pela URUTAU AI, abra **Vincular ou cadastrar cliente**. A seleção preenche os dados do orçamento e o rascunho guarda o vínculo. Se o cadastro mudar em outro dispositivo, a recuperação mantém os dados em edição até a confirmação de **Usar dados atuais do cadastro**. A emissão pode cadastrar o cliente junto com o orçamento ou continuar com os dados informados no formulário.

Ao confirmar um pedido ou abrir os detalhes de um registro antigo, selecione um cadastro ou opte por cadastrar com os dados do documento. A migração Flyway V8 adiciona o vínculo opcional e mantém os documentos antigos disponíveis para associação manual. Edições cadastrais preservam os dados, valores e PDFs já emitidos.

Os indicadores do cliente usam somente pedidos confirmados e ativos para unidades e valor aprovado. Os cancelamentos continuam nos históricos. O valor apresentado corresponde ao compromisso comercial, e a tabela de preços segue a quantidade total do orçamento. A configuração atual do Railway atende à V8.

## Gerenciamento de pedidos

Abra **Pedidos** para consultar orçamentos aguardando confirmação, pedidos em produção, pedidos entregues e registros cancelados. Pesquise pelo nome do cliente ou pelo número do orçamento ou do pedido. Os filtros e a ordenação usam a data de emissão, confirmação ou cancelamento conforme o estado.

Após negociar pelo WhatsApp, confirme o pedido manualmente. O sistema cria um pedido por orçamento, com numeração própria, e mantém os dados aprovados no orçamento e no PDF original. Informe o prazo previsto e as observações se necessário. Abra **Ver detalhes** para revisar os itens, consultar o histórico das operações ou editar o prazo e as observações de pedidos confirmados.

Para cancelar um orçamento em aberto ou um pedido, abra os detalhes e informe o motivo. O histórico registra a data e o administrador responsável. Os documentos continuam acessíveis e a numeração comercial é preservada. Compartilhar um PDF mantém o estado comercial atual.

Ao confirmar o pedido, o painel exibe **Em produção**. Abra os detalhes e selecione **Marcar como entregue** para registrar a entrega, com data e administrador. A confirmação comercial, sua data original e os indicadores do cliente são preservados. A migração V9 mantém pedidos confirmados anteriores em **Em produção** até o registro da entrega.

A migração Flyway V7 cria as estruturas de pedidos e eventos comerciais. Os orçamentos antigos continuam aguardando confirmação. A configuração atual do Railway atende à migração.

## URUTAU AI

Cole o pedido completo do catálogo oficial, incluindo o cabeçalho e o total, ou somente as linhas do pedido. O back-end lê o formato `• Jig Peixinho | 12g | Cor 05 | Qtd: 5`, valida as combinações ativas no banco, soma os itens e compara com o total declarado, quando presente. Um pedido estruturado e validado funciona mesmo sem uma chave de IA. As quantidades reconhecidas aparecem para revisão no mesmo formulário de orçamento. [Formato gerado pelo catálogo oficial](https://catalogo-urutau.vercel.app/script.js)

O provedor de IA interpreta mensagens livres e ajuda a esclarecer trechos ambíguos. Quando uma linha estruturada precisa de correção, a conversa indica a linha e mantém a revisão bloqueada até o esclarecimento.

### Ativar o Gemini na modalidade gratuita

1. Crie uma chave da API do Gemini em um projeto **Free Tier** no Google AI Studio. Use um projeto sem faturamento vinculado para permanecer na modalidade gratuita. [Criar chave](https://aistudio.google.com/api-keys) · [Modalidades e faturamento](https://aistudio.google.com/docs/billing)
2. Adicione ou atualize estas linhas no `.env` existente, mantendo os acessos administrativos e as demais configurações. Substitua o marcador pela chave criada, sem aspas externas.

   ```dotenv
   AI_PROVIDER=gemini
   GEMINI_API_KEY=COLE_A_CHAVE_DO_GOOGLE_AI_STUDIO
   GEMINI_MODEL=gemini-3.5-flash-lite
   ```

3. Pare a execução anterior. Na mesma pasta `urutau-jigs`, recarregue o `.env` com o trecho de PowerShell da seção de instalação e execute `java -jar ..\urutau-jigs-0.2.3.jar`. A mesma pasta de trabalho mantém o acesso a `data/urutau.mv.db` quando o H2 padrão estiver em uso. Em **Comercial**, abra **Novo orçamento com IA** e interprete um pedido de teste como `3 Spinner 12g cor 03`.

O modelo padrão, inclusive com `GEMINI_MODEL` em branco, é o Gemini 3.5 Flash-Lite estável. O back-end usa a operação oficial `generateContent` com Structured Outputs. Os limites de uso dependem do projeto no Google. Se a cota acabar ou o serviço falhar, a interface informa o erro e o gerador manual continua disponível. [Modelo e modalidade gratuita](https://aistudio.google.com/docs/models/gemini-3.5-flash-lite#pricing) · [Limites de uso](https://aistudio.google.com/docs/rate-limits)

### Provedor alternativo

Para escolher a OpenAI, configure explicitamente `AI_PROVIDER=openai` e `OPENAI_API_KEY`. `OPENAI_MODEL` é opcional e tem como padrão `gpt-4.1-mini`. Recarregue o ambiente e reinicie a aplicação depois de mudar o provedor. Cada requisição vai somente ao provedor escolhido, inclusive quando houver falha ou limite de uso.

### Uso e privacidade

Cole somente a parte da mensagem que descreve os itens. Informe nome, telefone, cidade e UF diretamente no formulário administrativo após a revisão. Antes de montar a requisição, o back-end descarta padrões de contato e filtra o texto e o histórico para aproveitar termos do pedido, como modelos do catálogo, quantidades, gramaturas e cores. O Google pode usar e revisar conteúdo da modalidade gratuita para melhorar seus produtos, portanto reserve a URUTAU AI para pedidos sem informação pessoal, sensível ou confidencial. [Termos do Gemini](https://aistudio.google.com/docs/terms#data-use-unpaid)

A conversa é vinculada à sessão administrativa, e **Nova conversa** cria outro contexto. O rascunho e as perguntas relevantes acompanham as mensagens seguintes até o encerramento ou expiração da sessão. Reiniciar o aplicativo inicia uma nova sessão.

As sugestões são validadas contra o catálogo e apresentadas no editor do gerador com IA quando estão prontas para revisão. A emissão acontece após a aprovação do administrador. O gerador manual funciona sem chave de IA e durante indisponibilidades do provedor.

## Estrutura

| Caminho | Responsabilidade |
| --- | --- |
| `src/main/java/br/com/urutau/jigs/config/` | Autenticação, sessão, CSRF e respostas de erro |
| `src/main/java/br/com/urutau/jigs/catalog/` | Modelos, variantes, preços e mínimo de atacado |
| `src/main/java/br/com/urutau/jigs/quotes/` | Validação, cálculos com `BigDecimal`, emissão e histórico |
| `src/main/java/br/com/urutau/jigs/pdf/` | Template comercial A4, grupos de itens, totais e paginação |
| `src/main/java/br/com/urutau/jigs/ai/` | Leitura do formato do catálogo, contexto, validação e adaptação ao Gemini ou OpenAI |
| `src/main/resources/db/migration/` | Schema, carga verificada e tabelas de orçamento |
| `src/main/resources/static/` | Painel, login e marca fornecida pela URUTAU JIGS |
| `src/test/java/` | Testes de integração e de comportamento |

## Testes

`mvn test` usa bancos H2 isolados. O conjunto cobre o cadastro dos preços iniciais por variante, a preservação de valores durante a migração, a edição administrativa, a classificação em torno do mínimo, os preços pendentes, os PDFs emitidos e os fluxos de interpretação com respostas de IA controladas.

Os testes de navegador executam o JAR gerado por `mvn clean verify` com um H2 descartável. Eles verificam o gerador manual, os PDFs, a autenticação, o Cache Storage e a atualização real do Service Worker no Chromium. A suíte simula os diálogos de instalação e compartilhamento, o modo instalado, a identificação do Safari e o acesso ao WhatsApp. A instalação física e o envio a um contato dependem do dispositivo. Com Python instalado, execute os comandos abaixo na raiz do projeto.

```powershell
python -m pip install -r src/test/browser/requirements.txt
python -m playwright install chromium
python src/test/browser/test_bulk_colors.py target/urutau-jigs-0.2.3.jar
python src/test/browser/test_whatsapp_share.py target/urutau-jigs-0.2.3.jar
python src/test/browser/test_pwa.py target/urutau-jigs-0.2.3.jar
python src/test/browser/test_mobile_layout.py target/urutau-jigs-0.2.3.jar --output target/auditoria-mobile
python src/test/browser/test_quote_confirmation.py target/urutau-jigs-0.2.3.jar
python src/test/browser/test_quote_drafts.py target/urutau-jigs-0.2.3.jar
python src/test/browser/test_orders.py target/urutau-jigs-0.2.3.jar
python src/test/browser/test_customers.py target/urutau-jigs-0.2.3.jar
python src/test/browser/test_workspaces.py target/urutau-jigs-0.2.3.jar
```

Se já tiver Chrome ou Edge instalado, use `--channel chrome` ou `--channel msedge` nos comandos de teste e dispense a instalação do Chromium pelo Playwright.

A suíte de responsividade testa as páginas e os diálogos com dados sintéticos de 320 a 1920 px, também em paisagem. Use `python -m playwright install firefox webkit` e execute a suíte com `--engine firefox` e `--engine webkit` para repetir a inspeção nesses motores. A opção `--baseline` registra achados de uma versão anterior para comparação. A emulação de teclado e toque complementa os testes em aparelhos físicos.

A suíte de confirmação testa emissão com a página rolada, prevenção de envio duplicado, falha de emissão e ações do PDF e WhatsApp. A suíte de rascunhos testa a recuperação em outra sessão do navegador, a edição simultânea, a perda de conexão e o fluxo com a URUTAU AI. Execute ambas também com `--engine webkit` para validar em WebKit, incluindo o modo standalone simulado.

O PDF usa fontes comerciais compatíveis com português brasileiro. Caracteres fora do repertório Windows-1252 são substituídos por `?` no documento.
