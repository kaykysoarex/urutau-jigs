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
   java -jar ..\urutau-jigs-0.1.3.jar
   ```

3. Abra [o painel local](http://127.0.0.1:8080/) e entre com uma das contas. Preencha os valores oficiais em **Tabela de preços** antes de emitir. O H2 guarda os registros em `data/` da pasta onde a aplicação foi iniciada.

Use `mvn spring-boot:run` para executar pelo código-fonte. Use `mvn clean verify` para executar os testes e gerar o JAR em `target/urutau-jigs-0.1.3.jar`. Em Linux, exporte as mesmas variáveis no ambiente do processo e execute o JAR com Java 21.

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
- O mínimo para lojista começa em 30 unidades e pode ser alterado em **Configurações**. Ao atingir o mínimo, o back-end recalcula todos os itens pela tabela de lojista.
- A remoção cadastral preserva o registro da combinação como inativo. Reativar a mesma combinação restaura o seu cadastro com o mesmo identificador.
- A emissão compara a revisão com os dados atuais do catálogo antes de gravar o orçamento. Cada emissão grava em uma transação os dados do cliente e os valores e descrições aplicados. A numeração vem do banco, e cada orçamento começa com status **Pendente**. O PDF é gerado a partir desses dados históricos.

## Pré-visualização do PDF

Com pelo menos um item no orçamento manual ou revisado pela URUTAU AI, clique em **Pré-visualizar PDF** para abrir ou baixar o rascunho. Os campos monetários sem valor aparecem como **A definir**. A identificação **PRÉVIA — SEM VALIDADE COMERCIAL** aparece em todas as páginas. O rascunho não é gravado no histórico e não recebe número oficial. Para **Emitir orçamento**, cadastre todos os preços da tabela aplicada e informe o nome do cliente.

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

3. Pare a execução anterior. Na mesma pasta `urutau-jigs`, recarregue o `.env` com o trecho de PowerShell da seção de instalação e execute `java -jar ..\urutau-jigs-0.1.3.jar`. A mesma pasta de trabalho mantém o acesso a `data/urutau.mv.db` quando o H2 padrão estiver em uso. Abra **URUTAU AI** e envie um pedido de teste como `3 Spinner 12g cor 03`.

O modelo padrão, inclusive com `GEMINI_MODEL` em branco, é o Gemini 3.5 Flash-Lite estável. O back-end usa a operação oficial `generateContent` com Structured Outputs. Os limites de uso dependem do projeto no Google. Se a cota acabar ou o serviço falhar, a interface informa o erro e o gerador manual continua disponível. [Modelo e modalidade gratuita](https://aistudio.google.com/docs/models/gemini-3.5-flash-lite#pricing) · [Limites de uso](https://aistudio.google.com/docs/rate-limits)

### Provedor alternativo

Para escolher a OpenAI, configure explicitamente `AI_PROVIDER=openai` e `OPENAI_API_KEY`. `OPENAI_MODEL` é opcional e tem como padrão `gpt-4.1-mini`. Recarregue o ambiente e reinicie a aplicação depois de mudar o provedor. Cada requisição vai somente ao provedor escolhido, inclusive quando houver falha ou limite de uso.

### Uso e privacidade

Cole somente a parte da mensagem que descreve os itens. Informe nome, telefone, cidade e UF diretamente no formulário administrativo após a revisão. Antes de montar a requisição, o back-end descarta padrões de contato e filtra o texto e o histórico para aproveitar termos do pedido, como modelos do catálogo, quantidades, gramaturas e cores. O Google pode usar e revisar conteúdo da modalidade gratuita para melhorar seus produtos, portanto reserve a URUTAU AI para pedidos sem informação pessoal, sensível ou confidencial. [Termos do Gemini](https://aistudio.google.com/docs/terms#data-use-unpaid)

A conversa é vinculada à sessão administrativa, e **Nova conversa** cria outro contexto. O rascunho e as perguntas relevantes acompanham as mensagens seguintes até o encerramento ou expiração da sessão. Reiniciar o aplicativo inicia uma nova sessão.

As sugestões são validadas contra o catálogo e encaminhadas ao formulário manual quando estão prontas para revisão. A emissão acontece após a aprovação do administrador. O gerador manual funciona sem chave de IA e durante indisponibilidades do provedor.

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

`mvn test` usa um banco H2 isolado e valores comerciais de teste. O conjunto cobre acesso administrativo, classificação abaixo e acima do mínimo, combinações e quantidades inválidas, preços pendentes, alteração de preços e preservação de orçamentos emitidos, paginação de PDFs e fluxos de interpretação com respostas de IA controladas.

O PDF usa fontes comerciais compatíveis com português brasileiro. Caracteres fora do repertório Windows-1252 são substituídos por `?` no documento.
