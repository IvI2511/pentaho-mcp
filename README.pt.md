<p align="center">
  <img src="docs/images/pentaho-live-icono.svg" alt="" width="88"><br><br>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/pentaho-live-oscuro.svg">
    <img src="docs/images/pentaho-live-claro.svg" alt="IDC Studios | Pentaho Live" height="34">
  </picture>
</p>

<p align="center">
  <strong>O Claude monta seus ETLs do Pentaho Data Integration (Spoon) ao vivo, enquanto você assiste.</strong><br>
  Um plugin do Spoon que sobe um servidor MCP local, mais um plugin do Claude Code com a skill para usá-lo.
</p>

<p align="center">
  <a href="https://github.com/IvI2511/pentaho-mcp/releases/latest"><img src="https://img.shields.io/github/v/release/IvI2511/pentaho-mcp?label=release&color=22d3a6" alt="Última release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-4f8cff" alt="Licença MIT"></a>
  <a href="#instalação"><img src="https://img.shields.io/badge/Claude%20Code-plugin-2c3542?logo=claude&logoColor=white" alt="Plugin do Claude Code"></a>
  <img src="https://img.shields.io/badge/PDI-9.4%20CE-3f74d8" alt="PDI 9.4 CE">
  <img src="https://img.shields.io/badge/MCP-local%20HTTP-1c9a80" alt="MCP">
</p>

<p align="center">
  <a href="README.md">English</a> · <a href="README.es.md">Español</a> · <strong>Português</strong>
</p>

> [!TIP]
> **Você é um agente (Claude Code, Codex…) e pediram para instalar isto?** Siga o [`INSTALL.md`](INSTALL.md).

<!-- GIF DE DEMONSTRAÇÃO: substituir este comentário e a linha abaixo por
<p align="center"><img src="docs/images/demo.gif" alt="Claude montando uma transformação ao vivo no Spoon" width="860"></p>
-->
<p align="center"><em>O GIF de demonstração chega em breve: o Claude monta uma transformação e cada step aparece no Spoon.</em></p>

## O que ele faz

Abra o Spoon, peça ao Claude um ETL com suas palavras e veja tudo acontecer no canvas: o Claude adiciona
os steps, conecta e configura cada um, confere os campos que saem de cada step, valida, mostra linhas
reais de preview e executa a transformação. Toda alteração passa pelo desfazer do próprio Spoon, então o
**Ctrl+Z** sempre funciona.

Sem o Spoon aberto? A skill continua útil: o Claude escreve o `.ktr` / `.kjb` e testa com o Pan ou o
Kitchen.

## O que você pode pedir

| Você diz… | O Claude… |
|---|---|
| *"Monte uma transformação que leia `ventas.csv`, traga a cidade de `clientes.csv`, some o valor por cidade e grave em um CSV."* | monta step por step no canvas, valida e mostra o resultado |
| *"Adicione um Filter rows depois do CSV input que mantenha as linhas com saldo > 1000 e mostre o preview."* | insere o step na transformação aberta e mostra as linhas que passam |
| *"Conecte no meu banco PostgreSQL e carregue este arquivo na tabela `clientes`."* | cria a conexão (você digita a senha no Spoon), lista tabelas e colunas e mapeia os campos |
| *"Crie um job que rode estas duas transformações em sequência e pare se a primeira falhar."* | monta o job com as entradas e os hops de sucesso / falha |
| *"O canvas está uma bagunça, organize."* | alinha os steps da esquerda para a direita, sem linhas cruzadas |
| *"Por que este `.ktr` falha no Pan?"* | lê o arquivo, encontra o problema na lista de erros verificados e corrige |

## Como funciona

```mermaid
flowchart LR
    you(["Você"])
    subgraph agent ["Claude Code"]
        claude["Claude"]
        skill["skill pentaho-etl<br/>formato .ktr/.kjb, catálogo de steps"]
    end
    subgraph pc ["Spoon (PDI 9.4) no seu computador"]
        plugin["plugin Pentaho Live<br/>servidor MCP local"]
        canvas["canvas do Spoon"]
        engine["engine do Kettle<br/>validar, preview, executar"]
    end
    you -- "pede" --> claude
    claude -. "consulta" .-> skill
    claude <-- "MCP via HTTP<br/>127.0.0.1:8765" --> plugin
    plugin <--> canvas
    plugin <--> engine
    canvas -- "você vê cada step aparecer" --> you
```

- O **plugin do Spoon** é um plugin de ciclo de vida do Kettle. Quando o Spoon abre, ele sobe um servidor
  MCP em `127.0.0.1:8765` com cerca de 50 ferramentas e aplica cada alteração na thread de interface do
  Spoon, com desfazer.
- O **plugin do Claude Code `pentaho-live`** conecta o Claude a esse servidor e inclui a skill
  `pentaho-etl`, para que o Claude use os ids de step e o XML exatos em vez de adivinhar.

## Instalação

**O jeito mais fácil: peça ao seu agente.** No Claude Code ou no Codex, digite:

> instale IvI2511/pentaho-mcp

O agente segue o [`INSTALL.md`](INSTALL.md): pergunta se você já tem o Pentaho, baixa o que falta,
instala o plugin do Spoon e se configura sozinho.

| Se você… | Baixe da [última release](https://github.com/IvI2511/pentaho-mcp/releases/latest) | Script do Windows |
|---|---|---|
| **já tem o PDI** | `pentaho-mcp.zip` (o plugin do Spoon, ~140 KB): descompacte em `data-integration/plugins/` | `instalar.ps1 -PdiHome <data-integration>` |
| **não tem o PDI** | `pdi-ce-9.4-con-pentaho-mcp.zip` (PDI 9.4 CE com o plugin já no lugar, ~370 MB) | `instalar.ps1 -InstalarPdi` |

Depois abra o Spoon, reinicie o Claude Code (ou rode `/reload-plugins`) e peça seu primeiro ETL.

<details>
<summary><strong>Instalação manual e outros agentes</strong></summary>

**Plugin do Claude Code:**

```
/plugin marketplace add IvI2511/pentaho-mcp
/plugin install pentaho-live@pentaho-tools
```

No app desktop do Claude: **Settings → Plugins → Add → marketplace** `IvI2511/pentaho-mcp` e depois
instale o **Pentaho Live** em **Discover**.

**Plugin do Spoon com o script, Windows (PowerShell):**

```powershell
Invoke-WebRequest -UseBasicParsing https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.ps1 -OutFile $env:TEMP\instalar-pentaho-mcp.ps1
powershell -ExecutionPolicy Bypass -File $env:TEMP\instalar-pentaho-mcp.ps1 -PdiHome "C:\caminho\para\data-integration"
```

Sem `-PdiHome` o script procura nas pastas de costume; `-InstalarPdi` baixa o PDI 9.4 CE com o plugin já
no lugar.

**macOS / Linux:**

```bash
curl -fsSL https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.sh -o /tmp/instalar-pentaho-mcp.sh
bash /tmp/instalar-pentaho-mcp.sh --pdi "/caminho/para/data-integration"   # ou --instalar-pdi
```

**Codex:** `codex mcp add pentaho --url http://127.0.0.1:8765/mcp` e copie `claude/skills/pentaho-etl`
para `~/.codex/skills/pentaho-etl`.

**Para testar:** com o Spoon aberto, `curl http://127.0.0.1:8765/ping` responde `{"ok":true,"spoon":true,...}`.

**Outra porta:** rode o script com `-Puerto 8770` (ele define `PENTAHO_MCP_PORT` para o Spoon) e mude a
opção `mcp_url` do plugin para `http://127.0.0.1:8770/mcp` em `/config`.

Códigos de saída, solução de problemas e todos os passos: [`INSTALL.md`](INSTALL.md) (em inglês).

</details>

## O que vem incluído

| Peça | O que é | Onde |
|---|---|---|
| **Plugin do Spoon** | `@LifecyclePlugin` do Kettle que sobe o servidor MCP quando o Spoon abre (bytecode Java 8, compilado contra o PDI 9.4) | [`spoon-plugin/`](spoon-plugin/) |
| **Skill `pentaho-etl`** | Formato `.ktr` / `.kjb`, catálogo de 216 steps e 77 job entries gerado a partir de uma instalação real, guia comentado dos steps mais usados, exemplos que rodam e os erros que o Pan e o Kitchen realmente dão | [`claude/skills/pentaho-etl/`](claude/skills/pentaho-etl/) |
| **Conexão MCP `pentaho`** | Aponta o Claude Code para o plugin do Spoon (endereço configurável com `mcp_url`) | [`claude/.mcp.json`](claude/.mcp.json) |
| **Instaladores** | Encontram ou baixam o PDI, instalam o plugin do Spoon e configuram o Claude Code e o Codex | [`instalar.ps1`](instalar.ps1), [`instalar.sh`](instalar.sh) |

<details>
<summary><strong>O que as ferramentas MCP cobrem</strong></summary>

| Área | O que o Claude pode fazer |
|---|---|
| Documentos | Ver o que está aberto; criar, abrir, salvar, alternar e fechar transformações e jobs |
| Steps e hops | Buscar um tipo de step e seu XML padrão; adicionar, configurar, renomear, mover, excluir e conectar steps; ativar ou desativar hops; escolher se um step copia ou distribui as linhas |
| Jobs | Adicionar, configurar e conectar entradas; hops de sucesso / falha / incondicionais |
| Campos | Listar os campos que saem de cada step, antes de configurar o próximo |
| Verificações | Validar; preview de linhas reais de qualquer step |
| Execução | Executar uma transformação ou um job, acompanhar o status e o log, parar |
| Bancos de dados | Listar tipos de conexão; criar, testar e excluir conexões; ver tabelas e colunas; rodar consultas SQL (somente leitura, a menos que você peça escrita) |
| Parâmetros | Listar, definir e excluir parâmetros |
| Canvas | Adicionar notas, selecionar steps, organizar o layout |

</details>

## Privacidade e segurança

Tudo roda no seu computador. Não há servidores, contas, analytics nem telemetria.

- A conexão MCP vai apenas para `http://127.0.0.1:8765/mcp`. O plugin do Spoon escuta só na interface de
  loopback e recusa requisições de páginas web de outras origens.
- As senhas dos bancos são mascaradas antes de qualquer coisa voltar para o Claude. O recomendado é você
  mesmo digitar as senhas no Spoon.
- Arquivos só são gravados onde você pede ao Claude para salvar; as transformações só rodam quando você
  pede.
- O que as ferramentas devolvem (steps, nomes de campos, logs, linhas de preview) passa a fazer parte da
  sua conversa com o Claude, como qualquer outra coisa que você compartilhe.

Política completa: [`PRIVACY.md`](PRIVACY.md) (em inglês).

## Compatibilidade

| | Status |
|---|---|
| Pentaho Data Integration | **9.4 CE** (testado com 9.4.0.0-343) |
| Java | **11 ou superior** (testado com Java 21) |
| Windows | Testado |
| macOS / Linux | Deve funcionar, ainda não testado: [relatos são bem-vindos](https://github.com/IvI2511/pentaho-mcp/issues) |
| Agentes | Claude Code (CLI e app desktop); Codex com a URL do MCP e a skill |

## Limitações

- **O ChatGPT na web não consegue se conectar**: ele só usa servidores MCP públicos via HTTPS, e este
  escuta em `127.0.0.1` de propósito. No chat do claude.ai só a skill é útil; para editar ao vivo é
  preciso um agente no mesmo computador em que o Spoon roda.
- **O PDI não traz drivers de MySQL, MariaDB, SQL Server nem Oracle** (traz de PostgreSQL, SQLite e H2).
  Copie o `.jar` do conector para `data-integration/lib/` e reinicie o Spoon (com o MySQL Connector/J 8+
  o tipo de conexão MySQL normal funciona).
- O PDI 9.4 CE da release não tem contêiner OSGi (karaf), então faltam os steps de big data e streaming
  que dependem dele. Os 216 steps do catálogo estão todos lá.
- Abra o Spoon antes de iniciar o agente; se o agente iniciou primeiro, reinicie-o para que ele veja as
  ferramentas.

## O que vem aí (0.4.0)

Em preparação para a próxima versão: um relatório do ETL em Markdown com diagrama Mermaid, uma ferramenta que
gera uma imagem do canvas, montar vários steps em uma só chamada, um único Ctrl+Z por ferramenta, nomes
de ferramentas em inglês e o catálogo de steps como recursos MCP. Detalhes no
[`CHANGELOG.md`](CHANGELOG.md).

## Desenvolvimento

| O quê | Como |
|---|---|
| Compilar o plugin do Spoon | `powershell -ExecutionPolicy Bypass -File spoon-plugin\build.ps1 -PdiHome <data-integration>` → `spoon-plugin/dist/` |
| Validar o plugin do Claude | `claude plugin validate --strict claude` e `claude plugin validate --strict .` |
| Regenerar o catálogo de steps | `bash tools/generador-catalogo/generar.sh <data-integration>` |
| Design interno (API do Spoon, threads, diálogos a evitar) | [`docs/api-spoon.md`](docs/api-spoon.md) (em espanhol) |

Contribuições são bem-vindas: veja o [`CONTRIBUTING.md`](CONTRIBUTING.md). A skill e a documentação
interna estão em espanhol; a ficha do plugin e a documentação para usuários, em inglês.

## Licença

[MIT](LICENSE) © IvI2511

---

<sub>Projeto comunitário independente. Não é afiliado, endossado nem suportado pela Hitachi Vantara.
Pentaho é uma marca da Hitachi Vantara. O PDI 9.4 CE é distribuído pela Hitachi Vantara sob a
licença Apache 2.0.</sub>
