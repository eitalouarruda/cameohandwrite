"""Offline Linux editor with durable, explicitly selected stroke resume."""
import asyncio, json, math, random, re, sys, threading, unicodedata
from pathlib import Path
from PySide6 import QtCore, QtGui, QtWidgets as Q
from bleak import BleakClient, BleakScanner

ROOT = Path(__file__).resolve().parent.parent
STORE = Path.home() / '.local/share/cameohandwrite/linux-job.json'
WRITE = '6d92661d-f429-4d67-929b-28e7a9780912'
READ = '8dcf199a-30e7-4bd4-beb6-beb57dca866c'
CONTROL = '61490654-b5b4-458c-a867-9e15bc1471e0'
def load_font(name):
    font = {}
    for line in (ROOT / f'app/src/main/assets/{name}.tsv').read_text().splitlines():
        c, w, path = line.split('\t'); paths = []
        for segment in path.split('M')[1:]:
            nums = list(map(float, re.findall(r'-?\d+(?:\.\d+)?', segment)))
            paths.append(list(zip(nums[::2], nums[1::2])))
        font[chr(int(c))] = (float(w), paths)
    return font

FONTS = load_font('futural')
CURSIVE = load_font('scripts')
STYLES = ('Muito feia', 'Feia', 'Cursiva', 'Anterior')

DEFAULTS = dict(width=210., height=297., left=25., right=15., top=10., bottom=15.,
                first_line=20., line_spacing=8., letter_height=4., word_space=2.,
                offset_x=0., offset_y=0.)

def settings(values=None):
    c = DEFAULTS | (values or {})
    if any(not isinstance(v,(float,int)) or not math.isfinite(v) for v in c.values()):
        raise ValueError('Medida inválida')
    if not (30<=c['width']<=305 and 30<=c['height']<=600): raise ValueError('Folha: 30–305 × 30–600 mm')
    if min(c[k] for k in ('left','right','top','bottom','word_space'))<0: raise ValueError('Margens negativas')
    if c['left']+c['right']>=c['width']: raise ValueError('Margens sem espaço para escrever')
    if not c['top']<=c['first_line']<=c['height']-c['bottom']: raise ValueError('Primeira linha fora da área útil')
    if not (1<=c['line_spacing']<=50 and 1<=c['letter_height']<=15): raise ValueError('Pauta ou letra fora dos limites')
    if max(abs(c['offset_x']),abs(c['offset_y']))>30: raise ValueError('Correção X/Y limitada a ±30 mm')
    return c

def layout(text, style="Muito feia", config=None):
    if style not in STYLES: raise ValueError("Fonte desconhecida")
    c=settings(config); left=c['left']; right=c['width']-c['right']; bottom=c['height']-c['bottom']
    pages = [[]]; x = left; y = c['first_line']; rng = random.Random(517)
    marks = {'́': [(-2,-3),(2,-7)], '̀': [(-2,-7),(2,-3)],
             '̂': [(-3,-3),(0,-7),(3,-3)], '̃': [(-4,-4),(-2,-6),(2,-3),(4,-5)],
             '̧': [(0,22),(1,25),(-2,27)], '̈': [(-3,-5),(-2,-5)]}
    for word in re.findall(r'\S+|\n', unicodedata.normalize('NFC', text)):
        if word == '\n': x = left; y += c['line_spacing']; continue
        glyphs = []
        for char in word:
            n = unicodedata.normalize('NFD', char)
            if n[0] not in FONTS: raise ValueError('Caractere ausente: ' + char)
            font = CURSIVE if style == 'Cursiva' or (style in ('Feia', 'Muito feia') and rng.random() < .35) else FONTS
            w, paths = font.get(n[0], FONTS[n[0]]); paths = [list(p) for p in paths]
            if len(n) > 1:
                if len(n) != 2 or n[1] not in marks: raise ValueError('Acento ausente: ' + char)
                paths.append([(w/2+a,b) for a,b in marks[n[1]]])
                if n[1] == '̈': paths.append([(w/2+3,-5),(w/2+4,-5)])
            sx = rng.uniform(.85,1.15); shear = rng.uniform(-.25,.25)
            amount = 1.0 if style == 'Muito feia' else .55 if style == 'Feia' else 0.
            sy = 1.; dy = 0.; bend = 0.
            if amount:
                sx += rng.uniform(-.16,.16)*amount
                shear += rng.uniform(-.35,.35)*amount
                sy += rng.uniform(-.22,.22)*amount
                dy = rng.uniform(-.9,.9)*amount
                bend = rng.uniform(-1.8,1.8)*amount
            transformed = [[((a*sx-(b-22)*shear+bend*((b-22)/21)**2)*c['letter_height']/21,
                              ((b-22)*sy+dy)*c['letter_height']/21) for a,b in p] for p in paths]
            bearing = min([0.] + [a for p in transformed for a,b in p])
            advance = w*sx*c['letter_height']/21 if style == 'Anterior' else max(w*sx*c['letter_height']/21, max([0.] + [a for p in transformed for a,b in p]))
            glyphs.append((advance-bearing, [[(a-bearing,b) for a,b in p] for p in transformed]))
        width = sum(w for w,p in glyphs)
        if width > right-left: raise ValueError('Palavra longa demais; separe com espaços')
        if x+width > right: x=left; y+=c['line_spacing']
        if y + max([0.] + [b for _,paths in glyphs for path in paths for a,b in path]) > bottom:
            pages.append([]); y=c['first_line']
        for w,paths in glyphs:
            for p in paths:
                pts=[(x+a+c['offset_x'],y+b+c['offset_y']) for a,b in p]
                if len(pts)>1:
                    if any(not(max(0,left+c['offset_x'])-1e-8<=a<=min(c['width'],right+c['offset_x'])+1e-8 and max(0,c['top']+c['offset_y'])<=b<=min(c['height'],bottom+c['offset_y'])) for a,b in pts): raise ValueError('Traço fora das margens')
                    pages[-1].append(pts)
            x+=w
        x+=c['word_space']
    return pages

class Window(Q.QMainWindow):
    signal = QtCore.Signal(str,int)
    def __init__(self):
        super().__init__(); self.setWindowTitle('Cameo Handwrite · Linux'); self.resize(1100,850)
        self.config=DEFAULTS.copy(); self.loading=False; self.pages=[]; self.progress=[]; self.stop=threading.Event(); self.active=False
        box=Q.QWidget(); self.setCentralWidget(box); v=Q.QVBoxLayout(box)
        toolbar=Q.QHBoxLayout(); v.addLayout(toolbar)
        self.font=Q.QComboBox(); self.font.addItems(STYLES); toolbar.addWidget(self.font,1)
        self.configure=Q.QPushButton('Medidas e avanço…'); toolbar.addWidget(self.configure)
        self.configure.clicked.connect(self.edit_settings)
        self.text=Q.QPlainTextEdit(); self.text.setPlaceholderText('Cole seu texto aqui'); v.addWidget(self.text,2)
        row=Q.QHBoxLayout(); v.addLayout(row)
        self.page=Q.QSpinBox(); self.page.setPrefix('Folha '); self.page.setMinimum(1); row.addWidget(self.page)
        self.resume=Q.QSpinBox(); self.resume.setPrefix('Retomar no traço '); self.resume.setMinimum(1); row.addWidget(self.resume)
        self.button=Q.QPushButton('Imprimir'); row.addWidget(self.button)
        self.cancel=Q.QPushButton('Pausar / cancelar'); row.addWidget(self.cancel)
        self.scene=Q.QGraphicsScene(); self.view=Q.QGraphicsView(self.scene); v.addWidget(self.view,5)
        self.scene.installEventFilter(self)
        self.status=Q.QLabel('A4 · Letras de 4 mm · Coloque a caneta no suporte 1, sem lâminas.'); v.addWidget(self.status)
        self.font.currentTextChanged.connect(self.rebuild); self.text.textChanged.connect(self.rebuild); self.page.valueChanged.connect(self.select)
        self.resume.valueChanged.connect(self.draw); self.button.clicked.connect(self.print_page)
        self.cancel.clicked.connect(self.stop.set); self.signal.connect(self.on_progress)
        if STORE.exists():
            try:
                saved=json.loads(STORE.read_text())
                self.config=settings(saved.get('config'))
                self.loading=True
                self.font.blockSignals(True); self.font.setCurrentText(saved.get('style','Anterior')); self.font.blockSignals(False)
                self.text.setPlainText(saved['text']); self.loading=False; self.rebuild()
                if len(saved['progress'])==len(self.pages): self.progress=saved['progress']
                self.select(); self.save()
            except Exception as e: self.status.setText(str(e))
    def summary(self):
        c=self.config
        return f"Folha {c['width']:g} × {c['height']:g} mm · Letra {c['letter_height']:g} mm · Pauta {c['line_spacing']:g} mm · Início Y {c['first_line']:g} mm · Correção X/Y {c['offset_x']:g}/{c['offset_y']:g} mm"
    def edit_settings(self):
        dialog=Q.QDialog(self); dialog.setWindowTitle('Medidas e avanço da escrita'); form=Q.QFormLayout(dialog)
        hint=Q.QLabel('Tudo em milímetros. X positivo move para a direita; Y positivo para baixo.\nA primeira linha é a base das letras. O avanço é a distância entre pautas.'); form.addRow(hint)
        labels=dict(width='Largura da folha',height='Altura da folha',left='Margem esquerda / início X',right='Margem direita',top='Margem superior',bottom='Margem inferior',first_line='Primeira linha / início Y',line_spacing='Avanço entre linhas',letter_height='Altura das letras',word_space='Espaço entre palavras',offset_x='Correção X',offset_y='Correção Y')
        fields={}
        for key,label in labels.items():
            field=Q.QDoubleSpinBox(); field.setDecimals(1); field.setSingleStep(.1); field.setRange(-30 if key.startswith('offset') else 0,600); field.setSuffix(' mm'); field.setValue(self.config[key]); form.addRow(label,field); fields[key]=field
        error=Q.QLabel(); error.setWordWrap(True); form.addRow(error)
        buttons=Q.QDialogButtonBox(Q.QDialogButtonBox.Save|Q.QDialogButtonBox.Cancel); form.addRow(buttons); buttons.rejected.connect(dialog.reject)
        def apply():
            try:
                config=settings({key:field.value() for key,field in fields.items()})
                layout(self.text.toPlainText(),self.font.currentText(),config)
                if config!=self.config:
                    if any(self.progress) and Q.QMessageBox.question(dialog,'Recalcular trabalho','Estas medidas mudam os trajetos e zeram a retomada deste trabalho. Aplicar?')!=Q.QMessageBox.Yes: return
                    self.config=config; self.rebuild()
                dialog.accept()
            except Exception as e: error.setText(str(e))
        buttons.accepted.connect(apply); dialog.exec()
    def save(self):
        STORE.parent.mkdir(parents=True,exist_ok=True); tmp=STORE.with_suffix('.tmp')
        tmp.write_text(json.dumps({'text':self.text.toPlainText(),'progress':self.progress,'style':self.font.currentText(),'config':self.config})); tmp.replace(STORE)
    def eventFilter(self,obj,event):
        if obj is self.scene and event.type()==QtCore.QEvent.GraphicsSceneMousePress and self.pages and not self.active:
            p=event.scenePos(); strokes=self.pages[self.page.value()-1]
            if strokes:
                index=min(range(len(strokes)),key=lambda j:min((x-p.x())**2+(y-p.y())**2 for x,y in strokes[j]))
                self.resume.setValue(index+1)
                self.status.setText('Retomada selecionada no traço '+str(index+1)+'. Confira a posição da folha antes de imprimir.')
        return super().eventFilter(obj,event)
    def rebuild(self):
        if self.loading: return
        try:
            self.pages=layout(self.text.toPlainText(),self.font.currentText(),self.config); self.progress=[0]*len(self.pages)
            self.page.setMaximum(len(self.pages)); self.select(); self.save()
            self.status.setText(self.summary())
        except Exception as e: self.pages=[]; self.status.setText(str(e)); self.scene.clear()
    def select(self):
        i=self.page.value()-1
        if i<len(self.pages):
            self.resume.setMaximum(max(1,len(self.pages[i])+1)); self.resume.setValue(self.progress[i]+1); self.draw()
    def draw(self):
        c=self.config
        self.scene.clear(); self.scene.addRect(0,0,c['width'],c['height'],QtGui.QPen(QtCore.Qt.gray),QtGui.QBrush(QtCore.Qt.white))
        y=c['first_line']
        while y<=c['height']-c['bottom']:
            self.scene.addLine(0,y,c['width'],y,QtGui.QPen(QtGui.QColor('#ddddff'),.15)); y+=c['line_spacing']
        self.scene.addRect(c['left'],c['top'],c['width']-c['left']-c['right'],max(0,c['height']-c['top']-c['bottom']),QtGui.QPen(QtGui.QColor('#eeaaaa'),.15))
        if self.pages:
            for j,stroke in enumerate(self.pages[self.page.value()-1]):
                path=QtGui.QPainterPath(QtCore.QPointF(*stroke[0]))
                for p in stroke[1:]: path.lineTo(*p)
                color='#32a477' if j<self.resume.value()-1 else '#222222'
                self.scene.addPath(path,QtGui.QPen(QtGui.QColor(color),.25))
        self.view.fitInView(self.scene.sceneRect(),QtCore.Qt.KeepAspectRatio)
    def on_progress(self,message,done):
        if message: self.status.setText(message)
        if done>=0:
            self.progress[self.page.value()-1]=done; self.resume.setValue(done+1); self.save()
        if done==-2:
            self.active=False
            for w in (self.text,self.font,self.configure,self.page,self.resume,self.button): w.setEnabled(True)
    def print_page(self):
        if not self.pages or self.active: return
        strokes=self.pages[self.page.value()-1]; start=self.resume.value()-1
        if start>=len(strokes): self.status.setText('Folha concluída. Selecione e carregue a próxima folha.'); return
        self.active=True; self.stop.clear()
        for w in (self.text,self.font,self.configure,self.page,self.resume,self.button): w.setEnabled(False)
        def work():
            try: asyncio.run(self.send(strokes,start))
            except Exception as e: self.signal.emit('Interrompido: '+str(e)+'. Confira o papel e ajuste a retomada.',-1)
            finally: self.signal.emit('',-2)
        threading.Thread(target=work,daemon=True).start()
    async def send(self,strokes,start):
        self.signal.emit('Buscando Cameo Bluetooth…',-1)
        devices=await BleakScanner.discover(timeout=8)
        choices=[d for d in devices if 'cameo' in (d.name or '').lower()]
        if len(choices)!=1: raise RuntimeError('Deixe exatamente uma Cameo visível; encontradas '+str(len(choices)))
        async with BleakClient(choices[0]) as client:
            queue=asyncio.Queue()
            await client.start_notify(READ,lambda c,b:queue.put_nowait(bytes(b)))
            await client.start_notify(CONTROL,lambda c,b:None)
            async def write(data):
                for i in range(0,len(data),20): await client.write_gatt_char(WRITE,data[i:i+20],response=True)
            async def query(data):
                while not queue.empty(): queue.get_nowait()
                await write(data); out=b''
                async with asyncio.timeout(8):
                    while b'\x03' not in out:
                        out+=await queue.get()
                        if len(out)>512: raise RuntimeError('Resposta excessiva')
                return out.split(b'\x03')[0].decode('ascii').strip()
            for c in (CONTROL,READ,WRITE): await client.write_gatt_char(c,b'\x1b\x04',response=True)
            await asyncio.sleep(1); model=await query(b'FG\x03')
            if not re.fullmatch(r'CAMEO\s+5\s+V[\d.]+',model,re.I): raise RuntimeError('Modelo recusado: '+model)
            if await query(b'\x1b\x05')!='0': raise RuntimeError('Carregue a folha e aguarde a Cameo ficar ociosa')
            async def ready():
                async with asyncio.timeout(60):
                    while True:
                        state=await query(b'\x1b\x05')
                        if state=='0': return
                        if state!='1': raise RuntimeError('Máquina sem folha ou estado desconhecido: '+state)
                        await asyncio.sleep(.05)
            if self.stop.is_set(): return
            await write(b'J1\x03FX1,1\x03!10,1\x03FC0,1,1\x03FE0,1\x03FF0,0,1\x03'); await ready()
            for j in range(start,len(strokes)):
                if self.stop.is_set(): self.signal.emit('Pausado. Confira a folha antes de retomar.',-1); return
                s=strokes[j]; cmd=lambda letter,p:f'{letter}{round(p[1]*20)},{round(p[0]*20)}\x03'.encode()
                data=cmd('M',s[0]); previous=s[0]
                for p in s[1:]:
                    part=cmd('D',p)
                    if len(data)+len(part)+30>512:
                        await write(data+cmd('M',previous)); await ready(); data=cmd('M',previous)
                    data+=part; previous=p
                await write(data+cmd('M',s[-1])); await ready()
                self.signal.emit(f'{model} · Traços confirmados: {j+1}/{len(strokes)}',j+1)
            self.signal.emit('Folha concluída. Carregue a próxima e selecione sua página para imprimir.',-1)
    def closeEvent(self,event):
        if self.active: self.stop.set(); event.ignore(); self.status.setText('Aguarde o bloco terminar antes de fechar.')
        else: event.accept()

if __name__=='__main__':
    app=Q.QApplication(sys.argv); w=Window(); w.show(); sys.exit(app.exec())
