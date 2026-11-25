# Instruções do projeto URUTAU JIGS

## Desenvolvimento e versionamento

Siga este fluxo ao implementar funcionalidades, corrigir problemas ou fazer outras alterações no projeto.

1. Analise o estado do repositório com `git status` antes de editar. Identifique a branch atual, o remoto configurado e as alterações que já estavam no diretório de trabalho.
2. Implemente as alterações solicitadas e preserve o trabalho do usuário. Inclua nos seus commits apenas os arquivos e trechos relacionados à tarefa.
3. Execute os testes automatizados. Use `mvn clean verify` para executar a suíte e validar a compilação. Corrija as falhas antes de enviar os commits.
4. Verifique se as funcionalidades existentes continuam funcionando, com atenção aos fluxos afetados.
5. Atualize o `README.md` quando a mudança afetar instalação, configuração, uso ou execução.
6. Revise as alterações com `git diff`. Antes de cada commit e envio, confira os arquivos preparados com `git diff --cached` e verifique as regras do `.gitignore`.
7. Crie commits organizados no padrão Conventional Commits. Separe alterações independentes por responsabilidade.
8. Execute `git push` para enviar os commits da branch atual ao remoto configurado.
9. Informe os commits realizados e o resultado do push. Em caso de conflito ou problema de autenticação, relate o ocorrido e o que ficou pendente.

## Mensagens de commit

Use o formato `tipo(escopo): descrição` ou `tipo: descrição`, com descrições em português, claras, objetivas e específicas. Evite mensagens genéricas como “atualização” ou “alterações”.

| Tipo | Uso |
| --- | --- |
| `feat` | Implementação de funcionalidades |
| `fix` | Correção de problemas |
| `refactor` | Reorganização do código |
| `style` | Alterações exclusivamente visuais |
| `docs` | Atualização da documentação |
| `test` | Implementação ou alteração de testes |
| `chore` | Manutenção e configuração do projeto |
| `perf` | Melhorias de desempenho |
| `security` | Melhorias de segurança |

## Segurança

Nunca versione arquivos `.env`, chaves de API, credenciais administrativas, bancos de dados locais, dados pessoais de clientes, arquivos JAR compilados ou arquivos temporários. Use valores fictícios no `.env.example`.

Preserve as alterações feitas pelo usuário. Nunca execute `git push --force` nem comandos destrutivos sem autorização explícita. Preserve o remoto configurado e o repositório existente. Qualquer mudança de endereço remoto ou criação de outro repositório exige autorização.

## Relatório de conclusão

Ao finalizar cada implementação, informe as funcionalidades implementadas, os arquivos modificados, os testes executados, a versão atual, os commits realizados, o resultado do push e as pendências, quando houver.
