# {{NAME}}

Projeto de ETL com Pentaho Data Integration 9.4 e [Pentaho Live](https://github.com/IvI2511/pentaho-mcp). Preparado por `pentaho-live init` (Pentaho Live {{VERSION}}).

## Pastas

| Pasta | O que vai nela |
|---|---|
| `etl/` | As transformações (`.ktr`) e os jobs (`.kjb`). |
| `datasets/` | Dados de entrada pequenos para desenvolver e testar (CSV, Excel, SQLite de teste). |
| `tests/` | Testes com datasets dourados (`*.test.json`): dados de entrada fixos e a saída esperada (`test_create` com o Claude, ou IDC > Testes… no Spoon; também `tests/` ao lado de uma `.ktr`). |
| `contracts/` | Contratos de dados das saídas (colunas, tipos, regras). |
| `env/` | Variáveis por ambiente: `dev.properties` é o exemplo compartilhado; os seus valores e senhas vão em `env/local.properties` (não vai para o repositório). |
| `.pentaho-live/` | O perfil do linter (`lint.yaml`) e o seu esquema. |
| `.github/workflows/` | A integração contínua: o linter, os testes e o diff semântico em cada pull request. |

## Convenções

`.pentaho-live/lint.yaml` as define, e elas são verificadas sem configurar nada pelo Claude (`lint_etl`), por "Revisar qualidade..." do menu IDC do Spoon, pela seção Qualidade dos relatórios, pelo corretor e pela CI:

- **Pontuação mínima**: cada `.ktr`/`.kjb` tem que chegar ao `min_score` do perfil.
- **Nomes**: cada step começa com o prefixo da sua categoria (`IN_` entradas, `OUT_` saídas, `LKP_` buscas, `FLT_` filtros...). "Revisar qualidade..." corrige sozinho: renomeia, e um Ctrl+Z desfaz.
- **Nada de caminhos nem hosts escritos à mão**: os caminhos começam com `${DIR_DATOS}` ou `${Internal.Entry.Current.Directory}`, e os hosts das conexões usam `${DB_HOST}`.
- **Senhas** nunca no `.ktr`: em `env/local.properties` ou em variáveis de ambiente.
- **Regras da equipe**: padrões em `team_rules` (por exemplo, nada em pastas temporárias).

## Verificar no seu computador

```sh
pentaho-live lint etl              # usa .pentaho-live/lint.yaml; sai com 1 se algum não chega ao mínimo
pentaho-live lint etl --format json
pentaho-live test --all            # cada *.test.json; sai com 1 se algum falha
pentaho-live test --all --lang pt  # o mesmo em português (en, es, pt; por padrão, o idioma do sistema)
pentaho-live diff --rev main --format markdown   # o que mudou em cada .ktr/.kjb desde main, por significado
```

No Spoon: **IDC > Revisar qualidade...** mostra "Perfil: {{NAME}}" e os achados das convenções com a correção.

## Integração contínua

`.github/workflows/pentaho-live.yml` instala o PDI CE 9.4 com o Pentaho Live (com cache) e, em cada pull request e push para `main`, passa o linter com o perfil em todo `etl/`, roda os testes com datasets dourados e escreve no resumo do job o diff semântico dos `.ktr`/`.kjb` alterados contra a branch base. O mesmo diff pode ir como comentário no pull request: defina `PENTAHO_LIVE_PR_COMMENT: "true"` e descomente `pull-requests: write` no workflow.

**Como ler uma execução.** Abra a execução na aba **Actions** do repositório (ou **Details** ao lado da verificação do pull request). Cada achado do linter e cada teste que não passa é uma anotação (`::error` / `::warning`) no seu arquivo: elas aparecem no quadro **Annotations** da execução e, num pull request, também em **Files changed** quando o arquivo faz parte dele. A página **Summary** da execução tem três seções: a nota de lint de cada arquivo, cada teste com a primeira diferença encontrada (chave, coluna, valor esperado e obtido) e o diff semântico dos `.ktr`/`.kjb` alterados. Um ✗ vermelho no passo dos testes quer dizer que uma saída dourada mudou: se a mudança foi de propósito, crie o teste de novo (`test_create` com `replace: true`) no mesmo pull request. Mais em [manual/13-proyecto.md](https://github.com/IvI2511/pentaho-mcp/blob/main/claude/skills/pentaho-etl/manual/13-proyecto.md#reading-a-ci-run).

## Diff legível no git

O `.gitattributes` já manda os `.ktr`/`.kjb` para o driver de diff `kettle`; rode uma vez em cada clone as linhas que `pentaho-live git-setup` imprime (`git config diff.kettle.textconv "pentaho-live textconv"`) e o `git diff` mostra as mudanças por step e não como XML. `pentaho-live normalize` grava os `.ktr`/`.kjb` em forma canônica: dois salvamentos sem mudanças reais não dão diff.
