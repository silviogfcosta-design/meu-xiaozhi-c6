# Equimos

Assistente próprio para ESP32-C6 e Android. O firmware e o aplicativo são desenvolvidos aqui; este projeto não usa nem clona a base Xiaozhi.

## Recursos

- Tela ST7789V 320 × 240 em paisagem, com rosto animado, relógio, clima, Lua e agenda.
- Áudio I2S do INMP441 e saída pelo MAX98357A; toque no GPIO2 e BOOT no GPIO9 iniciam/param a fala.
- Rede EQUIMOS-C6: áudio local entre placa e telefone; o app consulta a internet pela rede móvel.
- Gemini compreende a fala e gera resposta em português. A chave fica protegida pelo Android Keystore, fora do repositório. Sem chave ou cota gratuita, a IA não responde.
- Voz sintetizada pelo TTS do Android e enviada ao alto-falante do dispositivo.
- Clima de Sarandi/Porto Alegre via Open-Meteo, fase da Lua local, lembretes no Android, pesquisa de rádio pública e metadados de programas de TV.
- Atalhos para o seletor Cast do Android, Google Home e Google Maps. Para Chromecast/TV, telefone e TV precisam estar na mesma rede Wi-Fi; ao sair do Wi-Fi EQUIMOS-C6, o link de voz com a placa pausa. O controle depende da compatibilidade e configuração dos aparelhos/serviços.
- Os serviços online são gratuitos ou têm faixa gratuita, com limites próprios. As consultas passam pelos dados móveis do telefone e podem consumir a franquia da operadora.

## Ligações preservadas

| Módulo | ESP32-C6 |
|---|---:|
| ST7789V MOSI | GPIO7 |
| ST7789V SCK | GPIO6 |
| ST7789V DC | GPIO4 |
| ST7789V RST | GPIO3 |
| ST7789V CS | GPIO5 |
| INMP441 BCLK | GPIO18 |
| INMP441 WS | GPIO19 |
| INMP441 SD | GPIO20 |
| MAX98357A BCLK | GPIO18 |
| MAX98357A LRC | GPIO19 |
| MAX98357A DIN | GPIO14 |
| Toque | GPIO2 |
| BOOT | GPIO9 |

Cores registradas da tela: marrom CS, preto DC, branco RST, cinza MOSI, lilás SCK, azul VCC e verde GND. Cores dos fios de áudio não confirmadas. Mantenha a alimentação da montagem que já funcionou.

## Instalação

1. Abra Releases no repositório e escolha a prévia Equimos. Baixe Equimos-Android.apk e equimos-merged.bin. Não grave enquanto a conexão USB estiver instável.
2. Instale Equimos-Android.apk no telefone. Grave o firmware unido no ESP32-C6 no endereço 0x0.
3. Toque em Conectar ao Equimos e aceite EQUIMOS-C6 (senha equimos2026).
4. Mantenha os dados móveis habilitados. O telefone pode avisar que o Wi-Fi do Equimos não tem internet.
5. Para conversar, cole uma chave da API Gemini. Use a faixa gratuita e não vincule faturamento ao projeto da chave; o app não habilita faturamento nem usa fallback pago.

O firmware usa ESP-IDF 6.1, ESP32-C6 e flash de 4 MB. A compilação de CI confere o código; tela, microfone, amplificador e Wi-Fi ainda precisam de confirmação na placa física.

## Protocolo

TCP na porta 3761, cabeçalho de 5 bytes (tipo + tamanho big-endian). Áudio PCM mono, 16 kHz, 16 bits, little-endian.

- ESP → app: 0x01 início, 0x02 áudio, 0x03 fim.
- App → ESP: 0x20 estado JSON, 0x21 PCM para o alto-falante.

Firmware: ESP-IDF 6.1, idf.py -C firmware build. Android: JDK 17, Gradle, assembleDebug.

## Publicação automática

O workflow Equimos - publicar e compilar extrai os fontes deste pacote para firmware/ e android/, registra o código no repositório e compila os dois projetos. Quando ambos compilam, cria uma prévia em Releases com APK, firmware e código-fonte. Os arquivos de compilação deste pacote também permitem compilar os projetos separadamente.
