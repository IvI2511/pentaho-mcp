# {{NAME}}

Projeto de ETL com Pentaho Data Integration 9.4 e [Pentaho Live](https://github.com/IvI2511/pentaho-mcp). Preparado por `pentaho-live init` (Pentaho Live {{VERSION}}).

## Pastas

| Pasta | O que vai nela |
|---|---|
| `etl/` | As transformações (`.ktr`) e os jobs (`.kjb`). |
| `datasets/` | Dados de entrada pequenos para desenvolver e testar (CSV, Excel, SQLite de teste). |
| `tests/` | Testes com dados de entrada fixos e a saída esperada. |
| `contracts/` | Contratos de dados das saídas (colunas, tipos, regras). |
| `env/` | Variáveis por ambiente: `dev.properties` é o exemplo compartilhado; os seus valores e senhas vão em `env/local.properties` (não vai para o repositório). |
| `.pentaho-live/` | O perfil do linter (`lint.yaml`) e o seu esquema. |
| `.github/workflows/` | A integração contínua: o linter em cada pull request. |

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
```

No Spoon: **IDC > Revisar qualidade...** mostra "Perfil: {{NAME}}" e os achados das convenções com a correção.

## Integração contínua

`.github/workflows/pentaho-live.yml` instala o PDI CE 9.4 com o Pentaho Live (com cache) e passa o linter com o perfil em todo `etl/` em cada pull request. Os passos marcados `TODO` (testes com dados dourados e diff semântico) chegam com próximas versões do Pentaho Live.

## Diff legível no git

Com o Pentaho Live 0.7 ou posterior, `pentaho-live git-setup` imprime as linhas para o `git diff` mostrar as mudanças de um `.ktr` por step e não como XML (veja `.gitattributes`).
